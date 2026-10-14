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
     * @property account the account's tables of it (account.json): not the sealed logs, key wraps or recovery boxes.
     * @property sessions the sessions that opened; tombstones add none.
     * @property practiceNames custom practices' names, as sealed with their sessions.
     * @property unreadable sealed logs that did not open here (a key this phone lacks).
     * @property refused logs that opened but are unusable, or whose sealed time disagrees with the server's (a replay).
     */
    class Opened(
        val raw: ByteArray,
        val account: ByteArray,
        val sessions: List<Session>,
        val practiceNames: Map<String, String>,
        val unreadable: Int,
        val refused: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }

    /** The tables of the export that are the account's own (design: Data export and deletion). */
    val accountKeys = setOf(
        "exportedAt", "users", "auth_identities", "credentials", "devices", "device_lists", "sessions",
        "friendships", "invites", "invite_redemptions", "invite_tree", "admission_codes", "blocks",
        "reports", "streaks", "push_tokens",
    )

    /** The account id the export is for. */
    fun user(raw: ByteArray): UUID =
        UUID.fromString(json.parseToJsonElement(raw.decodeToString()).jsonObject.getValue("users").jsonObject.getValue("id").jsonPrimitive.content)

    /** `practiceKey` returns the key of a version, or null when this phone does not hold it. */
    fun open(raw: ByteArray, practiceKey: (Long) -> ByteArray?): Opened {
        val body = json.parseToJsonElement(raw.decodeToString()).jsonObject
        val user = user(raw)
        val sessions = mutableListOf<Session>()
        val names = mutableMapOf<String, String>()
        var unreadable = 0
        var refused = 0
        for (row in (body["practice_logs"] as? JsonArray).orEmpty()) {
            val log = row.jsonObject
            val sealed = log.string("sealed")?.let { Base64.getDecoder().decode(it) } ?: continue
            if (log.string("deletedAt") != null) continue
            val id = UUID.fromString(log.string("id"))
            val outer = log.string("clientUpdatedAt")?.let(SyncTime::parse)
            val version = log["keyVersion"]?.jsonPrimitive?.longOrNull
            if (outer == null || version == null) {
                refused++
                continue
            }
            when (val opened = SessionSync.open(id, sealed, version, outer, user, practiceKey)) {
                is SessionSync.Opened.Record -> if (opened.record.deletedAt == null) {
                    sessions += opened.record.session
                    opened.practiceName?.let { names[opened.record.session.practiceId] = it }
                }
                is SessionSync.Opened.Deletion -> {}
                SessionSync.Opened.Unreadable -> unreadable++
                SessionSync.Opened.Refused -> refused++
            }
        }
        val account = pretty.encodeToString(JsonObject.serializer(), JsonObject(body.filterKeys { it in accountKeys })).toByteArray()
        return Opened(raw, account, sessions, names, unreadable, refused)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull
}
