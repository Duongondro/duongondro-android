package app.duongondro.core

import app.duongondro.core.sync.SealedSession
import app.duongondro.core.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Corrections (design decision 34): into the total and rounds, never a day or the streak. */
class CorrectionTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val practice = Catalogue.builtIn.first { it.id == "dorje-sempa" }
    private val now = Instant.parse("2026-10-27T18:00:00Z")

    private fun session(amount: Int, at: Instant, correction: Boolean = false) = Session(
        practiceId = practice.id, amount = amount, startedAt = at, startExact = true, zoneId = zone.id, loggedAt = at, correction = correction,
    )

    @Test fun aCorrectionMovesTheTotalButNotTheStreak() {
        val p = TrackedPractice(practice, openingCount = 1_000)
        val sessions = listOf(session(1_200, now, correction = true))
        assertEquals(2_200, p.lifetime(sessions))
        assertEquals(0, Streak.of(practice.id, sessions, null, now, zone).current)
        assertEquals(0, Streak.headline(sessions, emptyList(), now, zone).current)
    }

    @Test fun aNegativeCorrectionLowersTheTotalAndNeverBelowZero() {
        val p = TrackedPractice(practice, openingCount = 500)
        assertEquals(200, p.lifetime(listOf(session(-300, now, correction = true))))
        assertEquals(0, p.lifetime(listOf(session(-900, now, correction = true))))
    }

    @Test fun aCorrectionRoundTripsSealedWithASignedCount() {
        val s = session(-300, now, correction = true)
        val sealed = SealedSession.of(SyncRecord(s, now), null)
        assertEquals(true, sealed.correction)
        val back = SealedSession.decode(sealed.encoded()).record(s.id)!!.session
        assertTrue(back.correction)
        assertEquals(-300, back.amount)
        // An ordinary session writes no correction key at all.
        assertFalse(SealedSession.of(SyncRecord(session(108, now), now), null).encoded().decodeToString().contains("correction"))
    }

    @Test fun onlyACorrectionMayBeNegative() {
        val json = """{"count":-5,"practice":"dorje-sempa","start":1791180000000,"tz":"Europe/Warsaw","updatedAt":1791180000000}"""
        assertNull(SealedSession.decode(json.toByteArray()).record(UUID.randomUUID()))
    }
}
