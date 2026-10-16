package app.duongondro.core.export

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The CSV export: a ZIP of RFC 4180 tables (UTF-8, a header row, CRLF), one per
 * kind of data, with headers in the app's language and numbers unformatted.
 * Dates are ISO 8601 (a day as `YYYY-MM-DD`; a session's start in the time zone
 * it started in, with its offset; the server's times in UTC), so a spreadsheet
 * in any locale reads them the same.
 */
object CsvExport {
    /** Every column of every table; the app names each in its language. */
    enum class Column {
        PRACTICE_NAME, PRACTICE_SECOND_NAME, TARGET, MALA_SIZE, STREAK_ONLY, ARCHIVED, TOTAL,
        SESSION_PRACTICE, DAY_COUNTED, START, COUNT, ROUND, AFTER_MIDNIGHT_CHOICE,
        FRIEND_NAME, FRIEND_SINCE,
        INVITE_ID, CREATED, EXPIRES, REVOKED, REDEEMED,
        ACCOUNT_ID, BLOCKED_SINCE, REASON, REPORTED_AT,
        DEVICE_ID, KEY_STORAGE, ADDED,
    }

    enum class Table(val file: String, val columns: List<Column>) {
        PRACTICES("practices.csv", listOf(Column.PRACTICE_NAME, Column.PRACTICE_SECOND_NAME, Column.TARGET, Column.MALA_SIZE,
            Column.STREAK_ONLY, Column.ARCHIVED, Column.TOTAL)),
        SESSIONS("sessions.csv", listOf(Column.SESSION_PRACTICE, Column.DAY_COUNTED, Column.START, Column.COUNT, Column.ROUND,
            Column.AFTER_MIDNIGHT_CHOICE)),
        FRIENDS("friends.csv", listOf(Column.FRIEND_NAME, Column.FRIEND_SINCE)),
        INVITES("invites.csv", listOf(Column.INVITE_ID, Column.CREATED, Column.EXPIRES, Column.REVOKED, Column.REDEEMED)),
        BLOCKS("blocks.csv", listOf(Column.ACCOUNT_ID, Column.BLOCKED_SINCE)),
        REPORTS("reports.csv", listOf(Column.ACCOUNT_ID, Column.REASON, Column.REPORTED_AT)),
        DEVICES("devices.csv", listOf(Column.DEVICE_ID, Column.KEY_STORAGE, Column.ADDED)),
    }

    /** One table's rows, in its columns' order. */
    fun rows(table: Table, content: ExportContent): List<List<Any?>> = when (table) {
        Table.PRACTICES -> content.practices.map {
            listOf(it.name, it.secondName, it.target, it.malaSize, it.streakOnly, it.archived, it.total)
        }
        Table.SESSIONS -> {
            val names = content.practices.associate { it.id to it.name }
            content.sessions.map {
                listOf(names[it.practiceId] ?: it.practiceId, it.day.toString(), start(it.startedAt, it.zone), it.count, it.round,
                    it.chosenDay?.toString())
            }
        }
        Table.FRIENDS -> content.friends.map { listOf(it.displayName, it.since?.let(::utc)) }
        Table.INVITES -> content.invites.map { listOf(it.id, utc(it.createdAt), utc(it.expiresAt), it.revokedAt?.let(::utc), it.redemptions) }
        Table.BLOCKS -> content.blocks.map { listOf(it.accountId.toString(), utc(it.since)) }
        Table.REPORTS -> content.reports.map { listOf(it.accountId?.toString(), it.reason, utc(it.createdAt)) }
        Table.DEVICES -> content.devices.map { listOf(it.id.toString(), it.tier, utc(it.addedAt)) }
    }

    fun table(table: Table, content: ExportContent, header: (Column) -> String): String =
        Csv.table(table.columns.map(header), rows(table, content))

    /** The ZIP: every table, even an empty one, so a script finds the same files every time. */
    fun zip(content: ExportContent, header: (Column) -> String, now: Instant = Instant.now()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (t in Table.entries) {
                z.putNextEntry(ZipEntry(t.file).apply { time = now.toEpochMilli() })
                z.write(table(t, content, header).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A session's start where it started: `2026-10-13T06:40:00+02:00`. */
    fun start(at: Instant, zone: ZoneId): String =
        ZonedDateTime.ofInstant(at.truncatedTo(ChronoUnit.SECONDS), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Whole-second UTC, as the JSON export writes it. */
    fun utc(at: Instant): String = at.truncatedTo(ChronoUnit.SECONDS).toString()
}
