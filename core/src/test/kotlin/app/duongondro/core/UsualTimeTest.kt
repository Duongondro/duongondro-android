package app.duongondro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** As iOS's UsualTimeTests. */
class UsualTimeTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun session(day: Int, hour: Int, minute: Int): Session {
        val t = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant()
        return Session(practiceId = "dorje-sempa", amount = 108, startedAt = t, startExact = false, zoneId = zone.id, loggedAt = t)
    }

    private val now = session(20, 12, 0).loggedAt

    @Test fun medianOfTheLastTwoWeeks() {
        val s = listOf(session(15, 7, 0), session(16, 7, 30), session(17, 8, 0), session(1, 22, 0))
        assertEquals("the session three weeks ago does not count", 7 * 60 + 30, UsualTime.minutes(s, now))
    }

    @Test fun tooFewSessionsAreNoHabit() {
        assertNull(UsualTime.minutes(listOf(session(18, 7, 0), session(19, 7, 0)), now))
    }

    @Test fun timesAroundMidnightWrap() {
        val s = listOf(session(15, 23, 50), session(16, 0, 10), session(17, 23, 40), session(18, 0, 20))
        val m = UsualTime.minutes(s, now)!!
        assertTrue("$m is not near midnight", m >= 23 * 60 + 45 || m <= 15)
    }

    @Test fun eachSessionIsReadInItsOwnZone() {
        // 07:00 in Amsterdam and 07:00 in New York are both a morning habit.
        val ny = ZoneId.of("America/New_York")
        val t = LocalDateTime.of(2026, 10, 18, 7, 0).atZone(ny).toInstant()
        val s = listOf(session(15, 7, 0), session(16, 7, 0), Session(practiceId = "dorje-sempa", amount = 1, startedAt = t,
            startExact = false, zoneId = ny.id, loggedAt = t))
        assertEquals(7 * 60, UsualTime.minutes(s, now))
    }
}
