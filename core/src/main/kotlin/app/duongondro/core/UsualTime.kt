package app.duongondro.core

import java.time.Duration
import java.time.Instant

/**
 * When a person usually finishes a practice, learned from their own log (design:
 * Social › Nudges, "Shouldn't you be meditating?"), as iOS's UsualTime: the
 * median wall-clock time at which sessions were logged over the last two weeks.
 * Nothing leaves the phone.
 */
object UsualTime {
    /** Sessions needed before there is a habit to speak of. */
    const val MINIMUM_SESSIONS = 3

    private const val DAY = 1440

    /**
     * Minutes after local midnight, or null without enough sessions. Times are
     * read in each session's own zone, so a trip does not move the habit. Around
     * midnight the times are taken as a circle: 23:50 and 00:10 average to
     * midnight, not noon.
     */
    fun minutes(sessions: List<Session>, now: Instant): Int? {
        val cutoff = now.minus(Duration.ofDays(14))
        // A correction is not a sitting: it says nothing about the practice time.
        val times = sessions.filter { !it.correction && it.loggedAt.isAfter(cutoff) && !it.loggedAt.isAfter(now) }.map { s ->
            val t = s.loggedAt.atZone(s.zone)
            t.hour * 60 + t.minute
        }
        if (times.size < MINIMUM_SESSIONS) return null
        // Rotate the circle so the widest gap between times sits at the cut, then
        // take an ordinary median and rotate back.
        val sorted = times.sorted()
        var cut = sorted[0]
        var widest = sorted[0] + DAY - sorted.last()
        for ((a, b) in sorted.zipWithNext()) {
            if (b - a > widest) {
                widest = b - a
                cut = b
            }
        }
        val rotated = sorted.map { (it - cut + DAY) % DAY }.sorted()
        val mid = rotated.size / 2
        val median = if (rotated.size % 2 == 1) rotated[mid] else (rotated[mid - 1] + rotated[mid]) / 2
        return (median + cut) % DAY
    }
}
