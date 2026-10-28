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
 * and numbers unformatted; the same tables and columns as iOS's CSVExport. A session's
 * start is `2026-10-13 06:40:00`, a form spreadsheets read as a date, where it started
 * (its zone in the next column); every other time is ISO 8601 with its offset in the
 * phone's zone (`2026-10-05T10:00:00+02:00`); days are `YYYY-MM-DD`. Value codes
 * (made/redeemed, hardware/tee/software, true/false) stay English, as README.txt says. Friends need an account; invites, blocks, reports and devices exist only
 * on the server, so their tables are left out without its part.
 */
object CsvExport {
    /** Every column of every table; with its English header (iOS's key); the app names each in its language. */
    enum class Column(val english: String) {
        PRACTICE("Practice"), SECOND_NAME("Second name"), TARGET("Target"), MALA_SIZE("Mala size"), STREAK_ONLY("Streak only"),
        ARCHIVED("Archived"),
        DAY_COUNTED("Day counted"), START("Start"), TIME_ZONE("Time zone"), COUNT("Count"), ROUND("Round"),
        CHOSEN_DAY("Day chosen after midnight"), CORRECTION("Correction"),
        DISPLAY_NAME("Display name"), FRIENDS_SINCE("Friends since"),
        CODE("Friendship code"), KIND("Made or redeemed"), CREATED("Created"), EXPIRES("Expires"), REVOKED("Revoked"),
        REDEMPTIONS("Redeemed"),
        PERSON("Person"), REASON("Reason"),
        DEVICE("Device"), KEY_STORAGE("Key storage"),
    }

    data class Table(val name: String, val columns: List<Column>, val rows: List<List<Any?>>) {
        val file: String get() = "$name.csv"
    }

    /** [aFriend] names a friend without a display name, as the app does. */
    fun tables(content: ExportContent, aFriend: String = "A friend"): List<Table> {
        val zone = content.zone
        fun time(at: Instant?) = at?.let { iso(it, zone) }
        val names = content.practices.associate { it.id to it.name }
        val out = mutableListOf(
            Table("practices", listOf(Column.PRACTICE, Column.SECOND_NAME, Column.TARGET, Column.MALA_SIZE, Column.STREAK_ONLY, Column.ARCHIVED),
                content.practices.map { listOf(it.name, it.secondName, it.target, it.malaSize, it.streakOnly, it.archived) }),
            Table("sessions", listOf(Column.PRACTICE, Column.DAY_COUNTED, Column.START, Column.TIME_ZONE, Column.COUNT, Column.ROUND, Column.CHOSEN_DAY, Column.CORRECTION),
                content.sessions.map {
                    // A correction counts for no day, so its day_counted is empty.
                    listOf(names[it.practiceId] ?: it.practiceId, it.day.toString().takeUnless { _ -> it.correction }, stamp(it.startedAt, it.zone), it.zone.id, it.count, it.round,
                        it.chosenDay?.let { _ -> it.day.toString() }, it.correction)
                }),
        )
        if (content.account != null) {
            out += Table("friends", listOf(Column.DISPLAY_NAME, Column.FRIENDS_SINCE),
                content.friends.map { listOf(it.displayName.ifEmpty { aFriend }, time(it.since)) })
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

    /** The ZIP: README.txt and one `<name>.csv` per table. */
    fun zip(content: ExportContent, header: (Column) -> String, now: Instant = Instant.now(), aFriend: String = "A friend"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun add(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name).apply { time = now.toEpochMilli() })
                z.write(bytes)
                z.closeEntry()
            }
            val tables = tables(content, aFriend)
            add("README.txt", readme(content).toByteArray(Charsets.UTF_8))
            for (t in tables) add(t.file, table(t, header).toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    /** What the tables are and how their values read; English, as the JSON's README, and worded as iOS's. */
    fun readme(content: ExportContent): String {
        val lines = mutableListOf(
            "Duongöndro: your data as tables",
            "",
            "One CSV file per kind of data: RFC 4180, UTF-8 (with a byte order mark), a",
            "header row in the app's language, CRLF line ends. Numbers are plain digits.",
            "A text cell that starts with =, +, -, @, a tab or a carriage return has a",
            "leading ' so a spreadsheet does not run it as a formula.",
            "",
            "Values that are codes stay English in every language: true and false;",
            "made and redeemed (friendship codes); hardware, tee and software (where a",
            "device keeps its key). Session starts are local to the session's time",
            "zone, given beside them; other times are ISO 8601 with their UTC offset.",
            "",
            "A session with correction true is a correction of the count: its count is",
            "the change, possibly negative, and it counts for no day or streak, so its",
            "day_counted is empty.",
        )
        when {
            content.account == null -> lines += listOf("", "This phone has no online account, so there are only practices and sessions.")
            !content.serverIncluded -> lines += listOf("", "The server could not be reached, so friends are as this phone knows them and",
                "there are no friendship codes, blocks, reports or devices. Export again online.")
        }
        if (content.unreadable > 0) {
            lines += listOf("", "${content.unreadable} sealed sessions could not be opened on this phone, so they",
                "are not in sessions.csv; the JSON export has them as the server stores them.")
        }
        return lines.joinToString("\n") + "\n"
    }

    /** "2026-10-13 06:40:00" in [zone]. */
    fun stamp(at: Instant, zone: ZoneId): String = STAMP.format(at.atZone(zone))

    /** "2026-10-05T10:00:00+02:00": whole seconds, with the offset of [zone]. */
    fun iso(at: Instant, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).atZone(zone))

    private val STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
}
