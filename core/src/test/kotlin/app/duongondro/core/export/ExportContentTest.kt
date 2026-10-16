package app.duongondro.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExportContentTest {
    private val content = ExportFixtures.content()

    @Test fun practicesInTheirOrderArchivedLast() {
        assertEquals(listOf("dorje-sempa", "chenrezig", "my-own"), content.practices.map { it.id })
    }

    @Test fun totalsRoundsAndStreaks() {
        val dorje = content.practices.first { it.id == "dorje-sempa" }
        assertEquals(111_219, dorje.total)
        assertEquals(2, dorje.round)
        assertEquals(108, dorje.inRound)
        assertTrue(dorje.public)
        // The 13th only (the 00:20 session counted for it): alive until midnight after the 14th, gone by the 15th's evening.
        assertEquals(0, dorje.currentStreak)
        assertEquals(1, dorje.longestStreak)
        val chenrezig = content.practices.first { it.id == "chenrezig" }
        assertTrue(chenrezig.streakOnly)
        assertNull(chenrezig.round)
        assertEquals(1, chenrezig.currentStreak)
        assertFalse(chenrezig.public)
    }

    @Test fun sessionsByPracticeThenStartWithTheirRound() {
        val dorje = content.sessions.filter { it.practiceId == "dorje-sempa" }
        assertEquals(listOf(1_000, 111, 108), dorje.map { it.count })
        // 110,000 opening + 1,000 = 111,000: round 1; + 111 = 111,111 ends round 1; + 108: round 2.
        assertEquals(listOf(1, 1, 2), dorje.map { it.round })
        val late = dorje.last()
        assertEquals(LocalDate.parse("2026-10-14"), late.startDay)
        assertEquals(LocalDate.parse("2026-10-13"), late.day)
        assertEquals(LocalDate.parse("2026-10-13"), late.chosenDay)
        assertEquals(listOf("dorje-sempa", "dorje-sempa", "dorje-sempa", "chenrezig"), content.sessions.map { it.practiceId })
    }

    @Test fun serverAccountWinsAndIsRead() {
        val a = content.account!!
        assertEquals("Tidy Raccoon", a.displayName)
        assertEquals("female", a.gender)
        assertEquals(listOf("raccoon@example.org"), a.emails)
        assertEquals("AQID", content.passkeys.single().id)
        assertEquals("hardware", content.devices.single().tier)
        assertEquals("Quiet Heron", content.friends.single().displayName)
        assertEquals(1, content.invites.single().redemptions)
        assertEquals(1, content.blocks.size)
        assertEquals("-spam, and \"worse\"", content.reports.single().reason)
        assertTrue(content.serverIncluded)
    }

    @Test fun withoutServerThePhonesOwnFacts() {
        val offline = ExportFixtures.content(withServer = false)
        assertEquals("Local name", offline.account!!.displayName)
        assertTrue(offline.devices.isEmpty())
        assertFalse(offline.serverIncluded)
    }

    @Test fun serverAccountToleratesMissingTables() {
        val a = ServerAccount.parse("""{"users":{"id":"nope"}}""".toByteArray())
        assertNull(a.displayName)
        assertTrue(a.friends.isEmpty() && a.devices.isEmpty() && a.passkeys.isEmpty())
    }

    @Test fun credentialIdsReadAsBase64url() {
        val a = ServerAccount.parse("""{"credentials":[{"id":"+/8=","createdAt":"2026-10-02T10:00:00Z"}]}""".toByteArray())
        assertEquals("-_8", a.passkeys.single().id)
    }
}
