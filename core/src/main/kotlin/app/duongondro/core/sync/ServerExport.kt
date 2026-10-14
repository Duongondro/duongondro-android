package app.duongondro.core.sync

import app.duongondro.core.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.UUID

/**
 * The server half of Settings › Your data › Export, as iOS's ServerExport: the
 * sealed practice logs of `GET /api/me/export` opened on this phone with the
 * practice keys (the same opening as sync), so the ZIP holds counts the server
 * never saw.
 */
object ServerExport {
    /**
     * @property raw the response, as sent (server-raw.json).
     * @property sessions the sessions that opened; tombstones add none.
     * @property unreadable sealed logs that did not open here (a key this phone lacks).
     */
    class Opened(val raw: ByteArray, val sessions: List<Session>, val unreadable: Int)

    private val json = Json { ignoreUnknownKeys = true }

    /** `practiceKey` returns the key of a version, or null when this phone does not hold it. */
    fun open(raw: ByteArray, practiceKey: (Long) -> ByteArray?): Opened {
        val body = json.parseToJsonElement(raw.decodeToString()).jsonObject
        val user = UUID.fromString(body.getValue("users").jsonObject.getValue("id").jsonPrimitive.content)
        val sessions = mutableListOf<Session>()
        var unreadable = 0
        for (row in (body["practice_logs"] as? JsonArray).orEmpty()) {
            val log = row.jsonObject
            val sealed = log.string("sealed")?.let { Base64.getDecoder().decode(it) } ?: continue
            if (log.string("deletedAt") != null) continue
            val id = UUID.fromString(log.string("id"))
            val outer = log.string("clientUpdatedAt")?.let(SyncTime::parse)
            val version = log["keyVersion"]?.jsonPrimitive?.longOrNull
            if (outer == null || version == null) {
                unreadable++
                continue
            }
            when (val opened = SessionSync.open(id, sealed, version, outer, user, practiceKey)) {
                is SessionSync.Opened.Record -> if (opened.record.deletedAt == null) sessions += opened.record.session
                is SessionSync.Opened.Deletion -> {}
                SessionSync.Opened.Unreadable, SessionSync.Opened.Refused -> unreadable++
            }
        }
        return Opened(raw, sessions, unreadable)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull
}
