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
        val files = unzip(DataExport.zip(snapshot, null, appVersion = "0.1 (1)"))
        // Cover photos never reach the server, so the export leaves them out (design decision 26).
        assertEquals(setOf("README.txt", "practice.json"), files.keys)
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

    /** Duongondro/duongondro-android#5: a session still in its undo window is not exported, nor counted in lifetime. */
    @Test fun aPendingSessionIsNotExported() {
        val open = Session(practiceId = "dorje-sempa", amount = 1_000, startedAt = Instant.ofEpochSecond(1_790_100_000),
            startExact = false, zoneId = ams, loggedAt = Instant.ofEpochSecond(1_790_100_000))
        val withPending = snapshot.copy(sessions = snapshot.sessions + open, pending = mapOf(open.id to Instant.ofEpochSecond(1_790_100_005)))
        val json = Json.parseToJsonElement(DataExport.practiceJson(withPending, "0.3", Instant.EPOCH)).jsonObject
        assertEquals(listOf(snapshot.sessions.single().id.toString()),
            json.getValue("sessions").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(608, json.getValue("practices").jsonArray[0].jsonObject.getValue("lifetime").jsonPrimitive.int)
    }

    @Test fun serverPartsPassThrough() {
        val server = DataExport.Server("""{"id":"a"}""".toByteArray(), """{"blobs":[]}""".toByteArray())
        val files = unzip(DataExport.zip(Snapshot(), server, appVersion = "0.1"))
        assertEquals(String(server.account), String(files.getValue("account.json")))
        assertEquals(String(server.raw), String(files.getValue("server-raw.json")))
    }

    @Test fun serverSessionsJoinPracticeJsonOnce() {
        val local = snapshot.sessions[0]
        val remote = Session(practiceId = "dorje-sempa", amount = 21, startedAt = Instant.ofEpochSecond(1_790_100_000),
            startExact = false, zoneId = ams, loggedAt = Instant.ofEpochSecond(1_790_100_000))
        val server = DataExport.Server("{}".toByteArray(), "{}".toByteArray(), listOf(local, remote), unreadable = 2)
        val files = unzip(DataExport.zip(snapshot, server, appVersion = "0.1"))
        val json = Json.parseToJsonElement(files.getValue("practice.json").decodeToString()).jsonObject
        assertEquals(listOf(108, 21), json.getValue("sessions").jsonArray.map { it.jsonObject.getValue("amount").jsonPrimitive.int })
        assertTrue(files.getValue("README.txt").decodeToString().contains("2 sealed sessions could not be opened"))
    }

    @Test fun aLocalDeletionNotYetPushedStaysDeleted() {
        val deleted = Session(practiceId = "dorje-sempa", amount = 5, startedAt = Instant.ofEpochSecond(1_790_200_000),
            startExact = true, zoneId = ams, loggedAt = Instant.ofEpochSecond(1_790_200_000))
        val server = DataExport.Server("{}".toByteArray(), "{}".toByteArray(), listOf(deleted))
        val merged = DataExport.merged(snapshot, server, known = setOf(deleted.id))
        assertEquals(snapshot.sessions, merged.sessions)
    }

    @Test fun anUnknownPracticeGetsACustomEntry() {
        val s = Session(practiceId = "my-practice", amount = 3, startedAt = Instant.ofEpochSecond(1_790_300_000),
            startExact = true, zoneId = ams, loggedAt = Instant.ofEpochSecond(1_790_300_000))
        val merged = DataExport.merged(snapshot, DataExport.Server("{}".toByteArray(), "{}".toByteArray(), listOf(s), mapOf("my-practice" to "My practice")))
        val added = merged.practices.last()
        assertEquals("my-practice", added.id)
        assertEquals("My practice", added.practice.name)
        assertTrue(added.practice.isCustom)
    }

    @Test fun anAccountWithoutItsServerPartIsNotLocalMode() {
        val files = unzip(DataExport.zip(Snapshot(), null, accountWithoutServer = true, appVersion = "0.1"))
        val readme = files.getValue("README.txt").decodeToString()
        assertTrue(readme.contains("has an account"))
        assertTrue(!readme.contains("local mode"))
    }
}
