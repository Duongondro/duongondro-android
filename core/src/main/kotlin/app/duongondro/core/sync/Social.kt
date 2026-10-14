package app.duongondro.core.sync

import app.duongondro.core.Session
import app.duongondro.core.Streak
import app.duongondro.core.api.ApiError
import app.duongondro.core.api.SignedStatementDto
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.StatementTypes
import app.duongondro.core.of
import app.duongondro.core.parseCivilDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A friend as this phone pinned them: the identity key their streaks must verify against. */
class PinnedFriend(
    val user: UUID,
    val identityPk: ByteArray,
    val displayName: String,
    val pinnedAt: Instant,
    /** False once the server stopped listing them; the pin stays until this person unfriends or blocks. */
    val listed: Boolean = true,
)

/**
 * What [Social] needs of the local database beyond sync, as iOS's AppDatabase:
 * pinned keys, the highest streak seq seen per friend and practice, and which
 * practices are public with the seq last published. Writes naming a generation
 * throw [Erased] after an erase, as sync's do.
 */
interface SocialDatabase {
    val generation: Int
    suspend fun syncState(): SyncState?

    suspend fun pinnedFriends(): List<PinnedFriend>
    /** Pins a key if none is pinned yet (the first key seen is kept), updates the name, marks them listed; returns the pin. */
    suspend fun pin(user: UUID, identityPk: ByteArray, displayName: String, at: Instant, generation: Int? = null): PinnedFriend
    /** Pins a key an invitation proved: it replaces any pin, and an empty name keeps the known one. */
    suspend fun repin(user: UUID, identityPk: ByteArray, displayName: String, at: Instant, generation: Int? = null)
    /** Marks pins the server no longer lists as unlisted, keeping them. */
    suspend fun keepFriends(listed: Set<UUID>, generation: Int? = null)
    /** Forgets a friend for good: only on this person's own unfriend or block. */
    suspend fun forgetFriend(user: UUID)
    /** The highest streak seq seen from a friend for a practice; 0 if none. */
    suspend fun seenSeq(user: UUID, practice: String): Long
    suspend fun noteSeq(user: UUID, practice: String, seq: Long, generation: Int? = null)

    /** Public practices and the seq of the last statement published for each. */
    suspend fun publicStreakSeqs(): Map<String, Long>
    suspend fun setPublic(practice: String, isPublic: Boolean)
    suspend fun isPublic(practice: String): Boolean
    suspend fun savePublishedSeq(practice: String, seq: Long, generation: Int? = null)
    /** Adds practices the server publishes for this account (another phone or a restore made them public), keeping the higher seq. */
    suspend fun mergePublished(seqs: Map<String, Long>, generation: Int? = null)
    /** The practice's live sessions, for its public streak. */
    suspend fun sessionsOf(practice: String): List<Session>
}

/**
 * Friends and public streaks on the phone (design: Social), as iOS's Social:
 * friends with keys pinned on first sight and every streak verified against the
 * pinned key, publishing this account's public streaks, and the friend actions.
 * Only streaks leave the phone, signed; counts never do.
 */
class Social(
    private val account: Account,
    private val db: SocialDatabase,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val api get() = account.api

    data class FriendStreak(
        val practice: String,
        val day: LocalDate?,
        val current: Int,
        val longest: Int,
        val deadline: Instant,
        val seq: Long,
    ) {
        /** The streak as it stands at [now]: a statement past its deadline is a streak that ended. */
        fun currentAt(now: Instant): Int = if (deadline.isAfter(now)) current else 0
    }

    data class FriendView(
        val user: UUID,
        val displayName: String,
        /** "male", "female" or "nonbinary", for conjugating; null when not given. */
        val gender: String?,
        val notifyDone: Boolean,
        /** The server names another key than the one pinned: nothing from this friend is shown until it is sorted out. */
        val keyChanged: Boolean,
        /** Verified, longest current first. */
        val streaks: List<FriendStreak>,
    )

    private suspend fun user(): UUID = db.syncState()?.user ?: throw AccountKeys.Error(AccountKeys.Failure.ACCOUNT_HAS_NO_KEYS)

    // Friends

    /**
     * The server's friend list, with keys pinned on first sight and every streak
     * verified against the pinned key; one that does not verify, or is older
     * than one already seen (a replay to deflate a streak), is dropped.
     */
    suspend fun friends(): List<FriendView> {
        val generation = db.generation
        val listed = api.friends()
        val statements = api.friendsStreaks()
        val pins = db.pinnedFriends().associateBy { it.user }.toMutableMap()
        for (f in listed) {
            val pin = pins[f.userId]
            if (pin != null && pin.displayName == f.displayName && pin.listed) continue
            val pk = f.identityPublicKey ?: pin?.identityPk ?: continue
            pins[f.userId] = db.pin(f.userId, pk, f.displayName, now(), generation)
        }
        db.keepFriends(listed.map { it.userId }.toSet(), generation)
        return listed.map { f ->
            val pin = pins[f.userId]
            val keyChanged = pin == null || (f.identityPublicKey != null && !f.identityPublicKey.contentEquals(pin.identityPk))
            val key = pin?.identityPk?.takeUnless { keyChanged }
            val streaks = if (key == null) emptyList() else statements.filter { it.userId == f.userId }.mapNotNull { s ->
                if (!E2EE.verifyStatement(StatementTypes.STREAK, s.payload, s.signature, key)) return@mapNotNull null
                val st = Statements.parseStreak(s.payload) ?: return@mapNotNull null
                if (st.user != f.userId || st.practice != s.practice) return@mapNotNull null
                if (st.seq < db.seenSeq(f.userId, st.practice)) return@mapNotNull null
                db.noteSeq(f.userId, st.practice, st.seq, generation)
                FriendStreak(st.practice, parseCivilDate(st.day), st.current, st.longest, st.deadline, st.seq)
            }.sortedByDescending { it.current }
            FriendView(f.userId, f.displayName, f.gender, f.notifyDone, keyChanged, streaks)
        }
    }

    /** Ends the friendship both ways and forgets the pinned key. */
    suspend fun unfriend(user: UUID) {
        try { api.unfriend(user) } catch (_: ApiError.NotFound) { }
        db.forgetFriend(user)
    }

    /** Blocks (ending any friendship, both ways) and forgets the pinned key. */
    suspend fun block(user: UUID) {
        api.block(user)
        db.forgetFriend(user)
    }

    /** Reports a friend to the people who run Duongöndro; the server takes at most 1000 characters. */
    fun report(user: UUID, reason: String) = api.report(user, reason.trim().take(REPORT_LIMIT))

    fun setNotifyDone(user: UUID, on: Boolean) = api.setNotifyDone(user, on)

    /**
     * A poke, one per friend per UTC day. Returns the UTC day it counts for:
     * a second poke the same day is answered 409, and counts as sent.
     */
    fun poke(user: UUID): LocalDate {
        try { api.poke(user) } catch (_: ApiError.Conflict) { }
        return utcDay(now())
    }

    // Public streaks

    private val publishing = Mutex()
    /** What was last sent for each practice, so an unchanged streak is not sent again. */
    private val lastPublished = mutableMapOf<String, String>()

    private fun content(s: Statements.Streak) = "${s.day} ${s.current} ${s.longest} ${s.deadline.syncMillis()}"

    /**
     * Publishes every public practice's streak whose content changed since the
     * last time; returns how many went up. The seq is the time in milliseconds,
     * kept above the last one sent. The server is the record of what is public:
     * another phone or a restore may have made a practice public.
     */
    suspend fun publishStreaks(): Int = publishing.withLock {
        val generation = db.generation
        val me = user()
        val identity = account.identity()
        val published = api.ownStreaks().mapNotNull { s -> Statements.parseStreak(s.payload)?.let { s.practice to it.seq } }.toMap()
        db.mergePublished(published, generation)
        var sent = 0
        for ((practice, lastSeq) in db.publicStreakSeqs()) {
            val at = now()
            val seq = maxOf(lastSeq + 1, at.syncMillis())
            var st = statement(practice, db.sessionsOf(practice), me, seq, at, zone()) ?: continue
            if (lastPublished[practice] == content(st)) continue
            // Made private while this publish ran: leave it.
            if (!db.isPublic(practice)) continue
            try {
                api.putStreak(practice, sign(st, identity))
            } catch (_: ApiError.Conflict) {
                // Another phone sent a higher seq: go above the server's.
                val stored = api.ownStreaks().firstOrNull { it.practice == practice }?.let { Statements.parseStreak(it.payload) }
                st = st.copy(seq = maxOf(st.seq, (stored?.seq ?: 0) + 1))
                api.putStreak(practice, sign(st, identity))
            }
            // Made private while the statement was on its way: take it back down.
            if (!db.isPublic(practice)) {
                try { api.deleteStreak(practice) } catch (_: ApiError.NotFound) { }
                continue
            }
            db.savePublishedSeq(practice, st.seq, generation)
            lastPublished[practice] = content(st)
            sent++
        }
        sent
    }

    /**
     * Makes a practice's streak public (published now) or private (removed from
     * the server). Private is set here first, so a publish already under way
     * sees it; it is restored if the server cannot be reached.
     */
    suspend fun setPublic(practice: String, isPublic: Boolean) {
        if (isPublic) {
            db.setPublic(practice, true)
            publishing.withLock { lastPublished.remove(practice) }
            publishStreaks()
        } else {
            db.setPublic(practice, false)
            try {
                api.deleteStreak(practice)
            } catch (_: ApiError.NotFound) {
            } catch (e: Exception) {
                db.setPublic(practice, true)
                throw e
            }
        }
    }

    private fun sign(st: Statements.Streak, identity: app.duongondro.core.crypto.Identity): SignedStatementDto {
        val payload = Statements.streak(st)
        val signed = SignedStatement.sign(StatementTypes.STREAK, payload, identity)
        return SignedStatementDto(signed.payload, signed.signature)
    }

    companion object {
        const val REPORT_LIMIT = 1000

        /** The UTC day the server counts pokes in. */
        fun utcDay(at: Instant): LocalDate = at.atZone(ZoneOffset.UTC).toLocalDate()

        /** What would be published for a practice: tracked days only, never a seed; null before its first session. */
        fun statement(practice: String, sessions: List<Session>, user: UUID, seq: Long, now: Instant, zone: ZoneId): Statements.Streak? {
            val r = Streak.of(practice, sessions, null, now, zone)
            val day = r.lastDay ?: return null
            val deadline = r.deadline ?: return null
            return Statements.Streak(user, practice, day.toString(), r.currentTracked, r.longestTracked, deadline.toSyncTime(), seq)
        }
    }
}

/**
 * The Friends tab's two lists, from verified streaks (iOS's FriendsView.news and
 * leaders), decided from the signed deadline rather than the day: a friend's day
 * is in their own zone.
 */
object FriendsBoard {
    data class NewsItem(val friend: Social.FriendView, val streak: Social.FriendStreak, val doneToday: Boolean)

    data class Leader(val friend: Social.FriendView, val streak: Social.FriendStreak)

    /**
     * One line per friend: the practice they did today, or else the streak still
     * waiting for today. The deadline is midnight after the day following the last
     * practice day, so more than a day left means they practised today (on their
     * own clock); less means today is still open. Done first, newest first.
     */
    fun news(friends: List<Social.FriendView>, now: Instant): List<NewsItem> =
        friends.filter { !it.keyChanged }.mapNotNull { f ->
            val live = f.streaks.filter { it.current > 0 && it.deadline.isAfter(now) }
            val done = live.filter { Duration.between(now, it.deadline) > Duration.ofDays(1) }.maxByOrNull { it.seq }
            when {
                done != null -> NewsItem(f, done, true)
                else -> live.maxByOrNull { it.current }?.let { NewsItem(f, it, false) }
            }
        }.sortedWith(compareBy<NewsItem> { if (it.doneToday) 0 else 1 }.thenByDescending { it.streak.seq })

    /** Every live public streak of every friend, longest first. */
    fun leaders(friends: List<Social.FriendView>, now: Instant): List<Leader> =
        friends.filter { !it.keyChanged }.flatMap { f ->
            f.streaks.filter { it.current > 0 && it.deadline.isAfter(now) }.map { Leader(f, it) }
        }.sortedByDescending { it.streak.current }
}
