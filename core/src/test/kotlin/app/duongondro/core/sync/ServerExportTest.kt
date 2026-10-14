package app.duongondro.core.sync

import app.duongondro.core.crypto.E2EE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import java.util.UUID

/** GET /api/me/export, canned: the sealed logs open under the practice keys, as in iOS's ServerExportTests. */
class ServerExportTest {
    private val user = UUID.randomUUID()
    private val practiceKey = E2EE.randomBytes(32)
    private val keys = { v: Long -> if (v == 1L) practiceKey else null }
    /** The rows' clientUpdatedAt, which the sealed time must equal. */
    private val t = java.time.Instant.parse("2026-10-04T13:20:00Z").toEpochMilli()

    private fun row(json: String, id: UUID, version: Long = 1, deleted: Boolean = false): String {
        val sealed = E2EE.sealSession(E2EE.sealKey(practiceKey, user), id, user, version, json.toByteArray())
        return """{"id":"$id","userId":"$user","sealed":"${Base64.getEncoder().encodeToString(sealed)}","keyVersion":$version,""" +
            """"clientUpdatedAt":"2026-10-04T13:20:00Z","deletedAt":${if (deleted) "\"2026-10-04T14:00:00Z\"" else "null"}}"""
    }

    private fun session(count: Int, start: Long) =
        """{"count":$count,"day":"2026-10-04","practice":"dorje-sempa","start":$start,"tz":"Europe/Amsterdam","updatedAt":$t}"""

    private fun canned(): ByteArray {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID(); val d = UUID.randomUUID()
        val rows = listOf(
            row(session(108, t), a),
            row(session(21, t + 1000), b),
            row(session(7, t + 2000), c, version = 2),
            row("""{"deletedAt":$t,"updatedAt":$t}""", d, deleted = true),
        )
        return ("""{"exportedAt":"2026-10-14T20:00:00Z","users":{"id":"$user","displayName":"Test"},""" +
            """"key_wraps":[{"x":1}],"recovery_boxes":[{"x":1}],"practice_logs":[${rows.joinToString(",")}],"friendships":[{"displayName":"Friend","gender":"f"}],"devices":[],"database_generation":"g"}""").toByteArray()
    }

    @Test fun opensTheSealedLogsAndKeepsTheResponseAsSent() {
        val raw = canned()
        val opened = ServerExport.open(raw, keys)
        assertArrayEquals(raw, opened.raw)
        val account = Json.parseToJsonElement(opened.account.decodeToString()).jsonObject
        assertEquals(setOf("exportedAt", "users", "friendships", "devices"), account.keys)
        assertEquals(0, opened.refused)
        assertEquals(listOf(21, 108), opened.sessions.map { it.amount }.sorted())
        assertEquals("a tombstone adds nothing; the key-version-2 log cannot open here", 1, opened.unreadable)
    }

    @Test fun aSealedTimeThatDisagreesIsRefused() {
        val wrong = """{"count":1,"practice":"dorje-sempa","start":1,"tz":"Europe/Amsterdam","updatedAt":5}"""
        val raw = ("""{"users":{"id":"$user"},"practice_logs":[${row(wrong, UUID.randomUUID())}]}""").toByteArray()
        val opened = ServerExport.open(raw, keys)
        assertEquals(1, opened.refused)
        assertEquals(0, opened.sessions.size)
    }
}
