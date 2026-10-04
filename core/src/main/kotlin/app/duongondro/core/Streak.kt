package app.duongondro.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The streak rules of `duongondro-api/docs/streaks.md`, tested against the
 * shared `streak-cases.json` (copied into the test resources unchanged).
 */
object Streak {
    sealed class Event {
        abstract val zone: ZoneId

        /** A logged session; `day` is the user's explicit choice in the after-midnight sheet. */
        data class Session(val start: Instant, override val zone: ZoneId, val day: LocalDate? = null) : Event()

        /** A bardo day covering `day`. */
        data class Bardo(val day: LocalDate, override val zone: ZoneId) : Event()
    }

    data class Seed(val days: Int, val lastDay: LocalDate, val zone: ZoneId)

    data class Result(
        val current: Int = 0,
        val currentTracked: Int = 0,
        val longest: Int = 0,
        val longestTracked: Int = 0,
        val lastDay: LocalDate? = null,
        val deadline: Instant? = null,
    )

    private class Resolved(val session: Boolean, val day: LocalDate, val zone: ZoneId, val at: Instant, val index: Int)

    fun compute(events: List<Event>, seed: Seed? = null, now: Instant, nowZone: ZoneId): Result {
        val resolved = events.mapIndexed { index, e ->
            when (e) {
                is Event.Session -> {
                    val day = e.day ?: civilDate(e.start, e.zone)
                    var at = e.start
                    if (e.day != null) {
                        val lastSecond = e.day.startOfDay(e.zone, 1).minusSeconds(1)
                        if (lastSecond < at) at = lastSecond
                    }
                    Resolved(true, day, e.zone, at, index)
                }
                is Event.Bardo -> Resolved(false, e.day, e.zone, e.day.startOfDay(e.zone), index)
            }
        }.sortedWith(compareBy<Resolved> { it.at }.thenBy { if (it.session) 0 else 1 }.thenBy { it.index })

        var started = false
        var count = 0
        var tracked = 0
        var longest = 0
        var longestTracked = 0
        var lastDay = LocalDate.of(1970, 1, 1)
        val zones = mutableListOf<ZoneId>()

        fun note() {
            longest = maxOf(longest, count)
            longestTracked = maxOf(longestTracked, tracked)
        }
        fun restart(day: LocalDate, zone: ZoneId) {
            started = true; count = 1; tracked = 1; lastDay = day
            zones.clear(); zones += zone
        }

        if (seed != null) {
            started = true
            count = seed.days
            tracked = 0
            lastDay = seed.lastDay
            zones += seed.zone
            note()
        }

        for (e in resolved) {
            when {
                !started -> if (e.session) restart(e.day, e.zone)
                // Same date, or an earlier one after flying west over the date line.
                !lastDay.isBefore(e.day) -> if (zones.none { it.id == e.zone.id }) zones += e.zone
                e.at < deadline(lastDay, zones, e.zone) -> {
                    if (e.session) { count += 1; tracked += 1 }
                    lastDay = e.day
                    zones.clear(); zones += e.zone
                }
                e.session -> restart(e.day, e.zone)
                else -> { started = false; count = 0; tracked = 0; zones.clear() }
            }
            note()
        }

        if (!started) return Result(longest = longest, longestTracked = longestTracked)
        val due = deadline(lastDay, zones, nowZone)
        val alive = now < due
        return Result(
            current = if (alive) count else 0,
            currentTracked = if (alive) tracked else 0,
            longest = longest,
            longestTracked = longestTracked,
            lastDay = lastDay,
            deadline = due,
        )
    }

    /** Midnight at the end of the day after `day`, in whichever zone gives the most time. */
    fun deadline(day: LocalDate, zones: List<ZoneId>, viewer: ZoneId): Instant =
        (zones + viewer).maxOf { day.startOfDay(it, 2) }
}
