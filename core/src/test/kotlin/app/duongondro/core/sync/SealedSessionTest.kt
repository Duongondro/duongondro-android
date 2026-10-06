package app.duongondro.core.sync

import app.duongondro.core.Session
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Random
import java.util.UUID

class SealedSessionTest {
    private val user = UUID.randomUUID()
    private val practiceKey = E2EE.randomBytes(32)
    private val keys = { v: Long -> if (v == 1L) practiceKey else null }
    private val t = 1_791_180_000_000L

    private fun session(start: Instant, logged: Instant = start, chosenDay: LocalDate? = null) = Session(
        practiceId = "dorje-sempa", amount = 108, startedAt = start, startExact = true,
        zoneId = "Europe/Amsterdam", chosenDay = chosenDay, loggedAt = logged,
    )

    private fun sealJson(json: String, id: UUID, version: Long = 1) =
        E2EE.sealSession(E2EE.sealKey(practiceKey, user), id, user, version, json.toByteArray())

    /** iOS testSealedSessionRoundTrips. */
    @Test fun aRecordRoundTrips() {
        val s = Session(practiceId = "dorje-sempa", amount = 108, startedAt = Instant.ofEpochSecond(1_791_176_400),
            startExact = false, zoneId = "Europe/Amsterdam", chosenDay = LocalDate.parse("2026-10-04"),
            loggedAt = Instant.ofEpochSecond(1_791_180_000))
        val record = SyncRecord(s, Instant.ofEpochSecond(1_791_180_001))
        val back = SealedSession.decode(SealedSession.of(record, null).encoded()).record(s.id)
        assertEquals(record, back)
    }

    /** The API vector's json is what this side writes for the same fields, byte for byte, and what it reads. */
    @Test fun theVectorsJsonIsCanonical() {
        val vectors = Json.parseToJsonElement(javaClass.getResource("/vectors.json")!!.readText()).jsonObject
        val vector = vectors["session"]!!.jsonObject["json"]!!.jsonPrimitive.content
        val written = SealedSession(count = 108, day = "2026-10-05", practice = "dorje-sempa", start = 1_791_176_400_000,
            tz = "Europe/Amsterdam", updatedAt = 1_791_180_000_000).encoded().decodeToString()
        assertEquals(vector, written)

        // iOS testTheVectorsSessionJSONApplies: only the specified fields, as another client seals them.
        val id = UUID.randomUUID()
        val opened = SessionSync.open(id, sealJson(vector, id), 1, syncInstant(t), user, keys) as SessionSync.Opened.Record
        assertEquals(108, opened.record.session.amount)
        assertFalse(opened.record.session.startExact)
        assertEquals(opened.record.session.startedAt, opened.record.session.loggedAt)
        assertEquals(LocalDate.parse("2026-10-05"), opened.record.session.day)
    }

    @Test fun aReplayUnderANewerOuterTimeIsRefused() {
        val id = UUID.randomUUID()
        val json = """{"count":108,"practice":"dorje-sempa","start":1791176400000,"tz":"Europe/Amsterdam","updatedAt":1791180000000}"""
        assertEquals(SessionSync.Opened.Refused, SessionSync.open(id, sealJson(json, id), 1, syncInstant(t + 60_000), user, keys))
    }

    @Test fun aMissingKeyVersionIsUnreadable() {
        val id = UUID.randomUUID()
        val json = """{"count":1,"practice":"dorje-sempa","start":1791176400000,"tz":"Europe/Amsterdam","updatedAt":1791180000000}"""
        assertEquals(SessionSync.Opened.Unreadable, SessionSync.open(id, sealJson(json, id, 2), 2, syncInstant(t), user, keys))
        // Moved to another session id, the blob does not open.
        assertEquals(SessionSync.Opened.Unreadable, SessionSync.open(UUID.randomUUID(), sealJson(json, id), 1, syncInstant(t), user, keys))
    }

    @Test fun aBareTombstoneDeletes() {
        val id = UUID.randomUUID()
        val json = """{"deletedAt":1791183600000,"updatedAt":1791183600000}"""
        assertEquals(
            SessionSync.Opened.Deletion(id, syncInstant(1_791_183_600_000), syncInstant(1_791_183_600_000)),
            SessionSync.open(id, sealJson(json, id), 1, syncInstant(1_791_183_600_000), user, keys),
        )
        // Neither content nor a deletion: refused.
        val empty = """{"updatedAt":1791183600000}"""
        assertEquals(SessionSync.Opened.Refused, SessionSync.open(id, sealJson(empty, id), 1, syncInstant(1_791_183_600_000), user, keys))
    }

    /** Content that opened under the key but is not a usable session is refused, not retried forever. */
    @Test fun authenticButUnusableContentIsRefused() {
        val id = UUID.randomUUID()
        // Sealed without the 0x80 marker: opens, but will not unpad.
        val unpadded = sealRaw(ByteArray(256), id)
        assertEquals(SessionSync.Opened.Refused, SessionSync.open(id, unpadded, 1, syncInstant(t), user, keys))
        assertEquals(SessionSync.Opened.Refused, SessionSync.open(id, sealJson("not json", id), 1, syncInstant(t), user, keys))
        val huge = """{"count":4294967296,"practice":"x","start":1,"tz":"UTC","updatedAt":5}"""
        assertEquals(SessionSync.Opened.Refused, SessionSync.open(id, sealJson(huge, id), 1, syncInstant(5), user, keys))
    }

    private fun sealRaw(plain: ByteArray, id: UUID) =
        E2EE.seal(E2EE.sealKey(practiceKey, user), plain, E2EE.sessionAAD(id, user, 1))

    @Test fun unknownFieldsAreIgnored() {
        val id = UUID.randomUUID()
        val json = """{"count":3,"minutes":20,"note":"later","practice":"x","start":1,"tz":"UTC","updatedAt":5}"""
        val opened = SessionSync.open(id, sealJson(json, id), 1, syncInstant(5), user, keys) as SessionSync.Opened.Record
        assertEquals(3, opened.record.session.amount)
    }

    /**
     * The millisecond rule (iOS testSealedAndOuterTimesAgreeAfterEveryRoundTrip):
     * for any sub-millisecond time, the sealed time and the outer RFC 3339 time
     * agree after the trip, and the record comes back at its nearest millisecond.
     * Rounding down got this wrong about one time in nine on iOS.
     */
    @Test fun sealedAndOuterTimesAgreeForManyRandomTimes() {
        val random = Random(20261007)
        repeat(2000) { i ->
            val base = Instant.ofEpochSecond(1_791_180_000 + random.nextInt(86_400 * 365).toLong(), random.nextInt(1_000_000_000).toLong())
            val updated = base.plusNanos(random.nextInt(1_000_000).toLong())
            val record = SyncRecord(session(base, base.plusNanos(random.nextInt(1_000_000_000).toLong())), updated,
                if (i % 7 == 0) updated else null)
            val log = SessionSync.seal(record, if (i % 3 == 0) "Custom ${"x".repeat(i % 40)}" else null, practiceKey, user, 1)

            // Through the wire: the server echoes the outer time, maybe with more digits or an offset.
            val wire = SyncTime.parse(log.updatedAtText)!!
            assertEquals(log.updatedAtText, 23 + 1, log.updatedAtText.length)
            assertEquals(record.updatedAt.syncMillis(), wire.toEpochMilli())

            val opened = SessionSync.open(log.id, log.sealed, 1, wire, user, keys)
            assertTrue("$opened at ${record.updatedAt}", opened is SessionSync.Opened.Record)
            val back = (opened as SessionSync.Opened.Record).record
            assertEquals(record.updatedAt.toSyncTime(), back.updatedAt)
            assertEquals(record.session.startedAt.toSyncTime(), back.session.startedAt)
            assertEquals(record.session.loggedAt.toSyncTime(), back.session.loggedAt)
            assertEquals(record.deletedAt?.toSyncTime(), back.deletedAt)
            assertEquals(record.deletedAt != null, log.deleted)

            // Applied once, sealed again by the other phone: the same times, the same JSON.
            val again = SessionSync.seal(back, opened.practiceName, practiceKey, user, 1)
            assertEquals(log.updatedAt, again.updatedAt)
            assertEquals(SealedSession.of(record, opened.practiceName), SealedSession.of(back, opened.practiceName))
        }
    }

    @Test fun roundingIsToTheNearestMillisecond() {
        val s = 1_791_180_000L
        assertEquals(s * 1000, Instant.ofEpochSecond(s, 0).syncMillis())
        assertEquals(s * 1000, Instant.ofEpochSecond(s, 499_999).syncMillis())
        assertEquals(s * 1000 + 1, Instant.ofEpochSecond(s, 500_000).syncMillis())
        assertEquals("a hair below its millisecond", s * 1000 + 1, Instant.ofEpochSecond(s, 999_999).syncMillis())
        assertEquals(s * 1000 + 1000, Instant.ofEpochSecond(s, 999_999_999).syncMillis())
        assertEquals(-1L, Instant.ofEpochSecond(-1, 999_000_000).syncMillis())
    }

    @Test fun outerTimesParseInEveryShapeGoWrites() {
        val ms = 1_791_180_000_123L
        assertEquals("2026-10-05T06:00:00.123Z", SyncTime.rfc3339(syncInstant(ms)))
        assertEquals("2026-10-05T06:00:00.000Z", SyncTime.rfc3339(syncInstant(1_791_180_000_000)))
        assertEquals("rounded, not truncated", "2026-10-05T06:00:00.124Z", SyncTime.rfc3339(Instant.ofEpochSecond(1_791_180_000, 123_600_000)))
        assertEquals(ms, SyncTime.parse("2026-10-05T06:00:00.123Z")!!.syncMillis())
        assertEquals(ms, SyncTime.parse("2026-10-05T06:00:00.123000000Z")!!.syncMillis())
        assertEquals(ms, SyncTime.parse("2026-10-05T06:00:00.122999999Z")!!.syncMillis())
        assertEquals(ms, SyncTime.parse("2026-10-05T08:00:00.123+02:00")!!.syncMillis())
        assertEquals(1_791_180_000_000L, SyncTime.parse("2026-10-05T06:00:00Z")!!.syncMillis())
        assertEquals(null, SyncTime.parse("yesterday"))
    }

    /** iOS testPaddingHidesTheLengthOfNames. */
    @Test fun paddingHidesTheLengthOfNames() {
        val s = session(Instant.ofEpochSecond(1_791_176_400)).copy(practiceId = "custom-a")
        val r = SyncRecord(s, s.loggedAt)
        val sizes = listOf("A", "B".repeat(40)).map { SessionSync.seal(r, it, practiceKey, user, 1).sealed.size }
        assertEquals(sizes[0], sizes[1])
        assertEquals(0, (sizes[0] - 12 - 16) % 256)
    }

    @Test fun eachSealIsFresh() {
        val r = SyncRecord(session(Instant.ofEpochSecond(1_791_176_400)), Instant.ofEpochSecond(1_791_176_400))
        val a = SessionSync.seal(r, null, practiceKey, user, 1)
        val b = SessionSync.seal(r, null, practiceKey, user, 1)
        assertFalse("random nonces", a.sealed.hex() == b.sealed.hex())
    }
}
