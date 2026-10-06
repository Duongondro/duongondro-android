package app.duongondro.core.sync

import app.duongondro.core.Session
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.parseCivilDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/** A session as sync carries it: its content, when it last changed, and when it was deleted. */
data class SyncRecord(val session: Session, val updatedAt: Instant, val deletedAt: Instant? = null)

/**
 * The session as sealed (docs/crypto.md in duongondro-api, Sealed session). A
 * tombstone may carry only its times, so everything else is optional on the way
 * in; readers ignore unknown fields. Times are Unix milliseconds, rounded to the
 * nearest (see SyncTime.kt). Properties are declared in key order, so the JSON
 * is written with sorted keys, as iOS writes it.
 */
@Serializable
data class SealedSession(
    val chosenDay: String? = null,
    val count: Long? = null,
    val day: String? = null,
    val deletedAt: Long? = null,
    val exact: Boolean? = null,
    val loggedAt: Long? = null,
    val practice: String? = null,
    val practiceName: String? = null,
    val start: Long? = null,
    val tz: String? = null,
    val updatedAt: Long,
) {
    fun encoded(): ByteArray = json.encodeToString(serializer(), this).toByteArray()

    /** The whole session, or null when the content is missing (a bare tombstone). */
    fun record(id: UUID): SyncRecord? {
        if (practice == null || count == null || start == null || tz == null) return null
        if (count !in 0..Int.MAX_VALUE.toLong()) return null
        val session = Session(
            id = id,
            practiceId = practice,
            amount = count.toInt(),
            startedAt = syncInstant(start),
            startExact = exact ?: false,
            zoneId = tz,
            chosenDay = chosenDay?.let(::parseCivilDate),
            loggedAt = syncInstant(loggedAt ?: start),
        )
        return SyncRecord(session, syncInstant(updatedAt), deletedAt?.let(::syncInstant))
    }

    companion object {
        internal val json = Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        /** What a record seals to; a custom practice's name travels with it so another phone can show it. */
        fun of(record: SyncRecord, practiceName: String?): SealedSession {
            val s = record.session
            return SealedSession(
                chosenDay = s.chosenDay?.toString(),
                count = s.amount.toLong(),
                day = s.day.toString(),
                deletedAt = record.deletedAt?.syncMillis(),
                exact = s.startExact,
                loggedAt = s.loggedAt.syncMillis(),
                practice = s.practiceId,
                practiceName = practiceName,
                start = s.startedAt.syncMillis(),
                tz = s.zoneId,
                updatedAt = record.updatedAt.syncMillis(),
            )
        }

        fun decode(bytes: ByteArray): SealedSession = json.decodeFromString(serializer(), bytes.decodeToString())
    }
}

/**
 * Sealing sessions for the server and opening what it sends back, as iOS's
 * SyncEngine does around its network calls. Last write wins on the client
 * clock; the server orders writes by the outer time, which it could forge, so
 * the sealed time, which it cannot, must agree with it.
 */
object SessionSync {
    /**
     * A sealed session ready for `PUT /logs/{id}`.
     *
     * @property updatedAt the outer time: exactly the millisecond sealed inside.
     */
    class SealedLog(val id: UUID, val sealed: ByteArray, val keyVersion: Long, val updatedAt: Instant, val deleted: Boolean) {
        /** The outer time as the API carries it: RFC 3339 with three fractional digits. */
        val updatedAtText: String get() = SyncTime.rfc3339(updatedAt)
    }

    fun seal(record: SyncRecord, practiceName: String?, practiceKey: ByteArray, user: UUID, keyVersion: Long): SealedLog {
        val json = SealedSession.of(record, practiceName).encoded()
        val sealed = E2EE.sealSession(E2EE.sealKey(practiceKey, user), record.session.id, user, keyVersion, json)
        return SealedLog(record.session.id, sealed, keyVersion, record.updatedAt.toSyncTime(), record.deletedAt != null)
    }

    sealed interface Opened {
        /** A whole session (possibly with deletedAt) and a custom practice's name. */
        data class Record(val record: SyncRecord, val practiceName: String?) : Opened

        /** A bare tombstone: only its times. */
        data class Deletion(val id: UUID, val updatedAt: Instant, val deletedAt: Instant) : Opened

        /** No key for this version, or it would not open under it: ask for it again later. */
        data object Unreadable : Opened

        /**
         * The sealed time disagrees with the outer one (an old blob replayed), or
         * the content opened but is unusable (bad padding, not a session, a count
         * out of range): skipped for good.
         */
        data object Refused : Opened
    }

    /**
     * Opens a log from the server. `practiceKey` returns the key of a version, or
     * null when this phone does not hold it.
     */
    fun open(
        id: UUID,
        sealed: ByteArray,
        keyVersion: Long,
        outerUpdatedAt: Instant,
        user: UUID,
        practiceKey: (Long) -> ByteArray?,
    ): Opened {
        val key = practiceKey(keyVersion) ?: return Opened.Unreadable
        val padded = try {
            E2EE.open(E2EE.sealKey(key, user), sealed, E2EE.sessionAAD(id, user, keyVersion))
        } catch (_: E2EE.Error) {
            return Opened.Unreadable
        }
        // Authentic but unusable content will never read better later: refusing
        // it (rather than calling it unreadable) lets the sync cursor move on.
        val session = try {
            SealedSession.decode(E2EE.unpad(padded))
        } catch (_: Exception) {
            return Opened.Refused
        }
        if (session.updatedAt != outerUpdatedAt.syncMillis()) return Opened.Refused
        if (session.count != null && session.count !in 0..Int.MAX_VALUE.toLong()) return Opened.Refused
        session.record(id)?.let { return Opened.Record(it, session.practiceName) }
        session.deletedAt?.let { return Opened.Deletion(id, syncInstant(session.updatedAt), syncInstant(it)) }
        return Opened.Refused
    }
}
