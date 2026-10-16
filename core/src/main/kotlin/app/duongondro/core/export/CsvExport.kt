package app.duongondro.core.export

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The CSV export: a ZIP of RFC 4180 tables (UTF-8 with a byte order mark, a header row,
 * CRLF), one per kind of data, with headers in the app's language, file names in English
 * and numbers unformatted; the same tables and columns as iOS's CSVExport. Times are
 * `2026-10-13 06:40:00`, a form spreadsheets read as a date: a session's start where it
 * started (its zone in the next column), everything else in the phone's zone; days are
 * `YYYY-MM-DD`. Friends need an account; invites, blocks, reports and devices exist only
 * on the server, so their tables are left out without its part.
 */
object CsvExport {
    /** Every column of every table; the app names each in its language. */
    enum class Column {
        PRACTICE, SECOND_NAME, TARGET, MALA_SIZE, STREAK_ONLY, ARCHIVED,
        DAY_COUNTED, START, TIME_ZONE, COUNT, ROUND, CHOSEN_DAY,
        DISPLAY_NAME, FRIENDS_SINCE,
        CODE, KIND, CREATED, EXPIRES, REVOKED, REDEMPTIONS,
        PERSON, REASON,
        DEVICE, KEY_STORAGE,
    }

    data class Table(val name: String, val columns: List<Column>, val rows: List<List<Any?>>) {
        val file: String get() = "$name.csv"
    }

    fun tables(content: ExportContent): List<Table> {
        val zone = content.zone
        fun time(at: Instant?, z: ZoneId = zone) = at?.let { stamp(it, z) }
        val names = content.practices.associate { it.id to it.name }
        val out = mutableListOf(
            Table("practices", listOf(Column.PRACTICE, Column.SECOND_NAME, Column.TARGET, Column.MALA_SIZE, Column.STREAK_ONLY, Column.ARCHIVED),
                content.practices.map { listOf(it.name, it.secondName, it.target, it.malaSize, it.streakOnly, it.archived) }),
            Table("sessions", listOf(Column.PRACTICE, Column.DAY_COUNTED, Column.START, Column.TIME_ZONE, Column.COUNT, Column.ROUND, Column.CHOSEN_DAY),
                content.sessions.map {
                    listOf(names[it.practiceId] ?: it.practiceId, it.day.toString(), time(it.startedAt, it.zone), it.zone.id, it.count, it.round,
                        it.chosenDay?.let { _ -> it.day.toString() })
                }),
        )
        if (content.account != null) {
            out += Table("friends", listOf(Column.DISPLAY_NAME, Column.FRIENDS_SINCE),
                content.friends.map { listOf(it.displayName, time(it.since)) })
        }
        if (content.serverIncluded) {
            out += Table("invites", listOf(Column.CODE, Column.KIND, Column.CREATED, Column.EXPIRES, Column.REVOKED, Column.REDEMPTIONS),
                content.invites.map {
                    listOf(it.id, if (it.made) "made" else "redeemed", time(it.createdAt), time(it.expiresAt), time(it.revokedAt),
                        if (it.made) it.redemptions else null)
                })
            out += Table("blocks", listOf(Column.PERSON, Column.CREATED), content.blocks.map { listOf(it.who, time(it.since)) })
            out += Table("reports", listOf(Column.PERSON, Column.REASON, Column.CREATED),
                content.reports.map { listOf(it.who, it.reason, time(it.createdAt)) })
            out += Table("devices", listOf(Column.DEVICE, Column.KEY_STORAGE, Column.CREATED),
                content.devices.map { listOf(it.id.toString().lowercase(), it.tier, time(it.addedAt)) })
        }
        return out
    }

    fun table(table: Table, header: (Column) -> String): String = Csv.table(table.columns.map(header), table.rows)

    /** The ZIP: one `<name>.csv` per table. */
    fun zip(content: ExportContent, header: (Column) -> String, now: Instant = Instant.now()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (t in tables(content)) {
                z.putNextEntry(ZipEntry(t.file).apply { time = now.toEpochMilli() })
                z.write(table(t, header).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** "2026-10-13 06:40:00" in [zone]. */
    fun stamp(at: Instant, zone: ZoneId): String = STAMP.format(at.atZone(zone))

    private val STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
}
