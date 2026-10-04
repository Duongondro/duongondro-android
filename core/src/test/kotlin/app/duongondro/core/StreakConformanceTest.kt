package app.duongondro.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Runs the shared streak cases from duongondro-api/testdata/streak-cases.json,
 * copied unchanged into the test resources. Go, Swift and Kotlin must all agree.
 */
class StreakConformanceTest {
    @Serializable data class SeedJson(val days: Int, val lastDay: String, val tz: String)
    @Serializable data class EventJson(val kind: String, val start: String? = null, val tz: String, val day: String? = null)
    @Serializable data class Expect(
        val current: Int, val currentTracked: Int, val longest: Int, val longestTracked: Int,
        val lastDay: String? = null, val deadline: String? = null,
    )
    @Serializable data class Case(
        val name: String, val seed: SeedJson? = null, val events: List<EventJson>,
        val now: String, val nowTz: String, val expect: Expect,
    )

    private fun instant(s: String): Instant = OffsetDateTime.parse(s).toInstant()

    @Test
    fun sharedCases() {
        val text = javaClass.getResource("/streak-cases.json")!!.readText()
        val cases = Json.decodeFromString<List<Case>>(text)
        assertTrue(cases.size >= 15)

        for (c in cases) {
            val events = c.events.map { e ->
                val zone = ZoneId.of(e.tz)
                when (e.kind) {
                    "session" -> Streak.Event.Session(instant(e.start!!), zone, e.day?.let { parseCivilDate(it)!! })
                    "bardo" -> Streak.Event.Bardo(parseCivilDate(e.day!!)!!, zone)
                    else -> error("${c.name}: unknown kind ${e.kind}")
                }
            }
            val seed = c.seed?.let { Streak.Seed(it.days, parseCivilDate(it.lastDay)!!, ZoneId.of(it.tz)) }
            val r = Streak.compute(events, seed, instant(c.now), ZoneId.of(c.nowTz))

            assertEquals("${c.name}: current", c.expect.current, r.current)
            assertEquals("${c.name}: currentTracked", c.expect.currentTracked, r.currentTracked)
            assertEquals("${c.name}: longest", c.expect.longest, r.longest)
            assertEquals("${c.name}: longestTracked", c.expect.longestTracked, r.longestTracked)
            assertEquals("${c.name}: lastDay", c.expect.lastDay, r.lastDay?.toString())
            assertEquals("${c.name}: deadline", c.expect.deadline?.let(::instant), r.deadline)
        }
    }

    @Test
    fun weekYearTrap() {
        // 2025-12-29 08:00 in Warsaw: week-based year 2026, calendar year 2025.
        assertEquals("2025-12-29", civilDate(Instant.ofEpochSecond(1_766_991_600), ZoneId.of("Europe/Warsaw")).toString())
    }
}
