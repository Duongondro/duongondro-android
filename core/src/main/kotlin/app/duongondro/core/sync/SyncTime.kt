package app.duongondro.core.sync

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Sync times are whole Unix milliseconds, rounded to the nearest at every
 * boundary: the sealed JSON, the outer RFC 3339 time, signed statements and
 * store comparisons. A time read back from a database or parsed from a string
 * can sit a hair below its millisecond, and rounding down would then name the
 * one before, so the sealed time and the outer one would disagree (iOS found
 * that about one session in nine did).
 */

/** This instant to the nearest millisecond (half a millisecond rounds up). */
fun Instant.syncMillis(): Long = Math.addExact(Math.multiplyExact(epochSecond, 1000L), (nano + 500_000L) / 1_000_000L)

/** The instant of a sync time in milliseconds. */
fun syncInstant(millis: Long): Instant = Instant.ofEpochMilli(millis)

/** This instant as the sync boundaries carry it: exactly its nearest millisecond. */
fun Instant.toSyncTime(): Instant = syncInstant(syncMillis())

object SyncTime {
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** RFC 3339 in UTC with exactly three fractional digits, the outer time the server stores. */
    fun rfc3339(instant: Instant): String = format.format(instant.toSyncTime())

    /** Any RFC 3339 time (Go writes nanoseconds and may write an offset), or null. */
    fun parse(text: String): Instant? = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
}
