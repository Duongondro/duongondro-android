package app.duongondro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class CoreTest {
    private val ams = "Europe/Amsterdam"
    private fun t(s: String) = Instant.parse(s)
    private fun builtIn(id: String) = Catalogue.builtIn.first { it.id == id }

    @Test fun catalogueGates() {
        val beginner = Catalogue.available(finishedNgondro = false, finishedShortRefuge = false).map { it.id }
        assertTrue("short-refuge" in beginner)
        assertFalse("dorje-sempa" in beginner)
        assertFalse("8th-karmapa" in beginner)
        assertTrue("16th-karmapa" in beginner)
        val inNgondro = Catalogue.available(finishedNgondro = false, finishedShortRefuge = true).map { it.id }
        assertTrue("mandala" in inNgondro)
        assertFalse("8th-karmapa" in inNgondro)
        assertTrue("short refuge stays open", "short-refuge" in inNgondro)
        val done = Catalogue.available(finishedNgondro = true, finishedShortRefuge = true).map { it.id }
        assertTrue("8th-karmapa" in done)
        assertTrue("repeat rounds stay available", "dorje-sempa" in done)
        assertTrue("short-refuge" in done)
    }

    @Test fun karmapaMeditationsStartStreakOnly() {
        val byId = Catalogue.builtIn.associateBy { it.id }
        assertFalse("short refuge is always counted", byId.getValue("short-refuge").streakOnlyAllowed)
        assertTrue(byId.getValue("16th-karmapa").streakOnlyByDefault)
        assertNull(byId.getValue("16th-karmapa").target)
        assertTrue(byId.getValue("8th-karmapa").streakOnlyByDefault)
        assertFalse(byId.getValue("chenrezig").streakOnlyByDefault)
        assertFalse(byId.getValue("dorje-sempa").streakOnlyByDefault)
    }

    @Test fun ngondroIsNeverStreakOnly() {
        assertFalse(Practice("x", "X", null, PracticeGroup.Ngondro, 111_111, true).streakOnlyAllowed)
        assertFalse(TrackedPractice(builtIn("dorje-sempa"), true).streakOnly)
        assertTrue(TrackedPractice(builtIn("chenrezig"), true).streakOnly)
        assertNull(TrackedPractice(builtIn("chenrezig"), true).rounds(emptyList()))
    }


    @Test fun rounds() {
        val r = RoundProgress.of(4 * 111_111 + 35_556, 111_111)
        assertEquals(5, r.round)
        assertEquals(35_556, r.inRound)
    }

    @Test fun malaOverride() {
        assertEquals(100, Practice("x", "X", null, PracticeGroup.AnyTime, null, true, malaSize = 100).effectiveMalaSize(108))
    }

    @Test fun pendingLogWindow() {
        val t0 = Instant.EPOCH
        var p = PendingLog.open("dorje-sempa", 108, t0)
        assertFalse(p.isDue(t0.plusSeconds(4)))
        p = p.add(108, t0.plusSeconds(4))
        assertEquals(216, p.amount)
        assertFalse(p.isDue(t0.plusSeconds(8)))
        assertTrue(p.isDue(t0.plusSeconds(9)))
    }

    @Test fun startEstimate() {
        val logged = Instant.ofEpochSecond(10_000)
        assertEquals(logged.minusSeconds(3600), SessionStart.estimate(logged, null, emptyList()))
        val lengths = listOf(600L, 1800L, 1200L).map(Duration::ofSeconds)
        assertEquals(logged.minusSeconds(1200), SessionStart.estimate(logged, null, lengths))
    }

    @Test fun dayIsTheStartsLocalDate() {
        val s = Session(practiceId = "dorje-sempa", amount = 108, startedAt = t("2026-10-04T21:30:00Z"),
            startExact = true, zoneId = ams, loggedAt = t("2026-10-04T22:30:00Z"))
        assertEquals("2026-10-04", s.day.toString())
    }

    @Test fun afterMidnightOffersTheOtherDay() {
        var s = Session(practiceId = "dorje-sempa", amount = 108, startedAt = t("2026-10-04T21:30:00Z"),
            startExact = false, zoneId = ams, loggedAt = t("2026-10-04T22:30:00Z"))
        val sheet = AfterMidnight.check(s)!!
        assertEquals("2026-10-04", sheet.countedFor.toString())
        assertEquals("2026-10-05", sheet.alternative.toString())
        s = s.copy(chosenDay = sheet.alternative)
        assertEquals("2026-10-05", s.day.toString())
        assertEquals("the switch goes both ways", "2026-10-04", AfterMidnight.check(s)!!.alternative.toString())
    }

    @Test fun noSheetForExactStartsOrSameDay() {
        assertNull(AfterMidnight.check(Session(practiceId = "x", amount = 1, startedAt = t("2026-10-04T21:30:00Z"),
            startExact = true, zoneId = ams, loggedAt = t("2026-10-04T22:30:00Z"))))
        assertNull(AfterMidnight.check(Session(practiceId = "x", amount = 1, startedAt = t("2026-10-04T10:00:00Z"),
            startExact = false, zoneId = ams, loggedAt = t("2026-10-04T11:00:00Z"))))
    }

    @Test fun lifetimeAndRoundsIncludeOpeningCount() {
        val ds = builtIn("dorje-sempa")
        val tracked = TrackedPractice(ds, openingCount = TrackedPractice.openingCount(5, 35_000, ds.target))
        val now = Instant.now()
        val sessions = listOf(
            Session(practiceId = "dorje-sempa", amount = 556, startedAt = now, startExact = true, zoneId = ams, loggedAt = now),
            Session(practiceId = "mandala", amount = 999, startedAt = now, startExact = true, zoneId = ams, loggedAt = now),
        )
        val r = tracked.rounds(sessions)!!
        assertEquals(5, r.round)
        assertEquals(35_556, r.inRound)
    }

    @Test fun openingCountClampsWithinALaterRound() {
        assertEquals(350_000, TrackedPractice.openingCount(1, 350_000, 111_111))
        assertEquals(2 * 111_111 - 1, TrackedPractice.openingCount(2, 350_000, 111_111))
        assertEquals(5, TrackedPractice.openingCount(0, 5, 111_111))
        assertEquals(5, TrackedPractice.openingCount(3, 5, null))
    }

    @Test fun headlineCountsAnyPractice() {
        val zone = ZoneId.of(ams)
        val a = Session(practiceId = "a", amount = 1, startedAt = t("2026-10-02T08:00:00Z"), startExact = true, zoneId = ams, loggedAt = t("2026-10-02T09:00:00Z"))
        val b = Session(practiceId = "b", amount = 1, startedAt = t("2026-10-03T08:00:00Z"), startExact = true, zoneId = ams, loggedAt = t("2026-10-03T09:00:00Z"))
        val now = t("2026-10-03T12:00:00Z")
        assertEquals(2, Streak.headline(listOf(a, b), emptyList(), now, zone).current)
        assertEquals(1, Streak.of("a", listOf(a, b), null, now, zone).current)
    }

    @Test fun seedCountsPrivatelyAndKeepsLongest() {
        val seed = StreakSeed("a", 40, 100, parseCivilDate("2026-10-02")!!, ams)
        val s = Session(practiceId = "a", amount = 1, startedAt = t("2026-10-03T08:00:00Z"), startExact = true, zoneId = ams, loggedAt = t("2026-10-03T09:00:00Z"))
        val r = Streak.of("a", listOf(s), seed, t("2026-10-03T12:00:00Z"), ZoneId.of(ams))
        assertEquals(41, r.current)
        assertEquals("public streaks count tracked days only", 1, r.currentTracked)
        assertEquals(100, r.longest)
    }

    @Test fun genderTravelsAsTheServerSpellsIt() {
        assertEquals(listOf("male", "female", "nonbinary"), Gender.entries.map { it.wire })
        assertEquals(Gender.Female, Gender.fromWire("female"))
        assertNull(Gender.fromWire(null))
        assertNull(Gender.fromWire("other"))
    }
}
