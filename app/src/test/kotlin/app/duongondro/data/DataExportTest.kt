package app.duongondro.data

import app.duongondro.core.Catalogue
import app.duongondro.core.Session
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import app.duongondro.core.parseCivilDate
import app.duongondro.model.Preferences
import app.duongondro.model.Snapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.ZipInputStream

class DataExportTest {
    private val ams = "Europe/Amsterdam"

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                put(e.name, z.readBytes())
            }
        }
    }

    private val snapshot = Snapshot(
        practices = listOf(TrackedPractice(Catalogue.builtIn.first { it.id == "dorje-sempa" }, openingCount = 500)),
        sessions = listOf(Session(practiceId = "dorje-sempa", amount = 108, startedAt = Instant.ofEpochSecond(1_790_000_000),
            startExact = true, zoneId = ams, loggedAt = Instant.ofEpochSecond(1_790_003_600))),
        seeds = listOf(StreakSeed("dorje-sempa", 3, null, parseCivilDate("2026-10-03")!!, ams)),
        preferences = Preferences(onboarded = true),
    )

    @Test fun localModeExport() {
        val files = unzip(DataExport.zip(snapshot, null, mapOf("dorje-sempa" to byteArrayOf(1, 2)), "0.1 (1)"))
        assertEquals(setOf("README.txt", "practice.json", "covers/dorje-sempa.jpg"), files.keys)
        val json = Json.parseToJsonElement(files.getValue("practice.json").decodeToString()).jsonObject
        assertEquals("duongondro/practice-export/v1", json.getValue("format").jsonPrimitive.content)
        assertEquals(608, json.getValue("practices").jsonArray[0].jsonObject.getValue("lifetime").jsonPrimitive.int)
        assertEquals("ngondro", json.getValue("practices").jsonArray[0].jsonObject.getValue("group").jsonPrimitive.content)
        val session = json.getValue("sessions").jsonArray[0].jsonObject
        assertEquals(108, session.getValue("amount").jsonPrimitive.int)
        assertEquals(ams, session.getValue("timeZone").jsonPrimitive.content)
        assertEquals(1, json.getValue("streakSeeds").jsonArray.size)
        assertTrue(files.getValue("README.txt").decodeToString().contains("local mode"))
    }

    @Test fun serverPartsPassThrough() {
        val server = DataExport.Server("""{"id":"a"}""".toByteArray(), """{"blobs":[]}""".toByteArray())
        val files = unzip(DataExport.zip(Snapshot(), server, appVersion = "0.1"))
        assertEquals(String(server.account), String(files.getValue("account.json")))
        assertEquals(String(server.raw), String(files.getValue("server-raw.json")))
    }
}
