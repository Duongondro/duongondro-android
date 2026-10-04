package app.duongondro.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * One logged session of one practice. Personal: inside the sealed blobs once
 * sync exists, never published. Carries no source (button, watch, custom).
 *
 * @property amount count added; 0 for a streak-only "done today".
 * @property startExact true when the user tapped Start, otherwise estimated.
 * @property chosenDay the user's choice in the after-midnight sheet; null means the start's own date.
 */
data class Session(
    val id: UUID = UUID.randomUUID(),
    val practiceId: String,
    val amount: Int,
    val startedAt: Instant,
    val startExact: Boolean,
    val zoneId: String,
    val chosenDay: LocalDate? = null,
    val loggedAt: Instant,
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))

    /** The day this session counts for: the local date it started on, unless the user chose. */
    val day: LocalDate get() = chosenDay ?: civilDate(startedAt, zone)

    val streakEvent: Streak.Event get() = Streak.Event.Session(startedAt, zone, chosenDay)
}

/**
 * The after-midnight sheet: a session logged after midnight whose estimated
 * start falls before it counts for the earlier day, and the app says so with a
 * one-tap switch to the other day. Nothing is backdated further than that.
 */
data class AfterMidnight(val countedFor: LocalDate, val alternative: LocalDate, val startedAround: Instant) {
    companion object {
        /** Null when there is nothing to say: an exact start, or start and logging on one date. */
        fun check(session: Session): AfterMidnight? {
            if (session.startExact) return null
            val started = civilDate(session.startedAt, session.zone)
            val logged = civilDate(session.loggedAt, session.zone)
            if (!started.isBefore(logged)) return null
            val counted = session.chosenDay ?: started
            return AfterMidnight(counted, if (counted == started) logged else started, session.startedAt)
        }
    }
}

/**
 * The undo window after +mala: a session is only written when the window
 * closes, so an undone tap leaves no record anywhere.
 */
data class PendingLog(val practiceId: String, val amount: Int, val openedAt: Instant, val deadline: Instant) {
    /** Another +mala on the same practice inside the window extends it. */
    fun add(more: Int, now: Instant): PendingLog = copy(amount = amount + more, deadline = now + WINDOW)

    fun isDue(now: Instant): Boolean = now >= deadline

    companion object {
        val WINDOW: Duration = Duration.ofSeconds(5)

        fun open(practiceId: String, amount: Int, now: Instant) = PendingLog(practiceId, amount, now, now + WINDOW)
    }
}

/**
 * Estimated start of a session logged at the end: exact when the user tapped
 * Start, otherwise the logging time minus the typical session length.
 */
object SessionStart {
    val DEFAULT_LENGTH: Duration = Duration.ofHours(1)

    fun estimate(loggedAt: Instant, tappedStart: Instant?, timedLengths: List<Duration>): Instant =
        tappedStart ?: (loggedAt - (median(timedLengths) ?: DEFAULT_LENGTH))

    /** Lengths of the sessions the user timed with Start. */
    fun timedLengths(sessions: List<Session>): List<Duration> =
        sessions.filter { it.startExact }.map { Duration.between(it.startedAt, it.loggedAt) }.filter { !it.isNegative && !it.isZero }

    internal fun median(xs: List<Duration>): Duration? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]).dividedBy(2)
    }
}

/** A practice the user tracks: a catalogue entry plus their own settings. */
data class TrackedPractice(
    val practice: Practice,
    private val wantsStreakOnly: Boolean = false,
    /** Count brought in from paper or a spreadsheet, across all rounds. */
    val openingCount: Int = 0,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
) {
    val id: String get() = practice.id

    /** A "done today" check with no count (never for ngöndro). */
    val streakOnly: Boolean get() = practice.streakOnlyAllowed && wantsStreakOnly

    fun lifetime(sessions: List<Session>): Int =
        maxOf(0, openingCount) + sessions.filter { it.practiceId == id }.sumOf { it.amount }

    /** Round progress, or null for open-ended or streak-only practices. */
    fun rounds(sessions: List<Session>): RoundProgress? {
        val target = practice.target
        if (streakOnly || target == null || target <= 0) return null
        return RoundProgress.of(lifetime(sessions), target)
    }

    companion object {
        /**
         * The opening count for someone in `round` (1-based) with `inRound` done.
         * In round 1 the count may run past the target (a lifetime total typed in
         * one go); in a later round it stops short of the target.
         */
        fun openingCount(round: Int, inRound: Int, target: Int?): Int {
            if (target == null || target <= 0) return maxOf(0, inRound)
            val r = maxOf(1, round)
            val count = if (r > 1) minOf(maxOf(0, inRound), target - 1) else maxOf(0, inRound)
            return (r - 1) * target + count
        }
    }
}

/**
 * A private streak seed from onboarding: counts on the user's own Today, never
 * in public streaks or the leaderboard.
 */
data class StreakSeed(val practiceId: String, val days: Int, val longest: Int? = null, val lastDay: LocalDate, val zoneId: String) {
    val streakSeed: Streak.Seed get() = Streak.Seed(days, lastDay, runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC")))
}

/** One practice's streak from its sessions and optional seed. */
fun Streak.of(practiceId: String, sessions: List<Session>, seed: StreakSeed?, now: Instant, zone: ZoneId): Streak.Result {
    val r = compute(
        events = sessions.filter { it.practiceId == practiceId }.map { it.streakEvent },
        seed = seed?.takeIf { it.practiceId == practiceId }?.streakSeed,
        now = now, nowZone = zone,
    )
    val longest = seed?.longest ?: return r
    return r.copy(longest = maxOf(r.longest, longest))
}

/**
 * The headline streak on Today: days with any practice. With several seeds,
 * the one that gives the longest current streak wins.
 */
fun Streak.headline(sessions: List<Session>, seeds: List<StreakSeed>, now: Instant, zone: ZoneId): Streak.Result {
    val events = sessions.map { it.streakEvent }
    return (listOf<Streak.Seed?>(null) + seeds.map { it.streakSeed })
        .map { compute(events, it, now, zone) }
        .maxWith(compareBy<Streak.Result> { it.current }.thenBy { it.longest })
}
