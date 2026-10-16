package app.duongondro.data

import android.content.Context
import app.duongondro.R
import app.duongondro.core.civilDate
import app.duongondro.core.export.CsvExport
import app.duongondro.core.export.ExportContent
import app.duongondro.core.export.PdfReport
import app.duongondro.core.export.ServerAccount
import app.duongondro.model.Snapshot
import app.duongondro.ui.shownNames
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The three formats of Settings › Your data › Export (design decision 26): PDF to read
 * or print, CSV for a spreadsheet, JSON with everything (Art. 20). All are made on the
 * phone, from the local database alone when there is no account or no connection.
 */
enum class ExportFormat(val mime: String) {
    PDF("application/pdf"),
    CSV("application/zip"),
    JSON("application/zip");

    fun fileName(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day = civilDate(now, zone)
        return when (this) {
            PDF -> "duongondro-export-$day.pdf"
            CSV -> "duongondro-export-$day-csv.zip"
            JSON -> DataExport.fileName(now, zone)
        }
    }
}

object ExportFiles {
    /**
     * What PDF and CSV show: the phone's practices and final sessions, the server's sealed
     * sessions merged in, and the account's part of the server export when it came.
     * [knownSessionIds] keeps sessions deleted here out (see [DataExport.merged]).
     */
    fun content(
        context: Context, snapshot: Snapshot, server: DataExport.Server?, knownSessionIds: Set<UUID>,
        account: ExportContent.AccountFacts?, cachedFriends: List<ExportContent.FriendRow>,
        now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault(),
    ): ExportContent {
        val s = DataExport.merged(snapshot, server, knownSessionIds).withoutPending()
        return ExportContent.build(
            practices = s.practices, sessions = s.sessions, seeds = s.seeds, publicPractices = s.publicPractices,
            account = account, server = server?.let { ServerAccount.parse(it.account) }, cachedFriends = cachedFriends,
            now = now, zone = zone, names = { it.shownNames(context) },
        )
    }

    fun pdf(context: Context, content: ExportContent, version: String, now: Instant = Instant.now()): ByteArray {
        val out = ByteArrayOutputStream()
        PdfExport(context).write(PdfReport.build(content, PdfExport.text(context, version, now)), out)
        return out.toByteArray()
    }

    fun csv(context: Context, content: ExportContent, now: Instant = Instant.now()): ByteArray =
        CsvExport.zip(content, { context.getString(header(it)) }, now)

    /** Each CSV column's header in the app's language. */
    fun header(column: CsvExport.Column): Int = when (column) {
        CsvExport.Column.PRACTICE_NAME -> R.string.csv_practice_name
        CsvExport.Column.PRACTICE_SECOND_NAME -> R.string.csv_practice_second_name
        CsvExport.Column.TARGET -> R.string.csv_target
        CsvExport.Column.MALA_SIZE -> R.string.csv_mala_size
        CsvExport.Column.STREAK_ONLY -> R.string.csv_streak_only
        CsvExport.Column.ARCHIVED -> R.string.csv_archived
        CsvExport.Column.TOTAL -> R.string.csv_total
        CsvExport.Column.SESSION_PRACTICE -> R.string.csv_session_practice
        CsvExport.Column.DAY_COUNTED -> R.string.csv_day_counted
        CsvExport.Column.START -> R.string.csv_start
        CsvExport.Column.COUNT -> R.string.csv_count
        CsvExport.Column.ROUND -> R.string.csv_round
        CsvExport.Column.AFTER_MIDNIGHT_CHOICE -> R.string.csv_after_midnight
        CsvExport.Column.FRIEND_NAME -> R.string.csv_friend_name
        CsvExport.Column.FRIEND_SINCE -> R.string.csv_friend_since
        CsvExport.Column.INVITE_ID -> R.string.csv_invite_id
        CsvExport.Column.CREATED -> R.string.csv_created
        CsvExport.Column.EXPIRES -> R.string.csv_expires
        CsvExport.Column.REVOKED -> R.string.csv_revoked
        CsvExport.Column.REDEEMED -> R.string.csv_redeemed
        CsvExport.Column.ACCOUNT_ID -> R.string.csv_account_id
        CsvExport.Column.BLOCKED_SINCE -> R.string.csv_blocked_since
        CsvExport.Column.REASON -> R.string.csv_reason
        CsvExport.Column.REPORTED_AT -> R.string.csv_reported_at
        CsvExport.Column.DEVICE_ID -> R.string.csv_device_id
        CsvExport.Column.KEY_STORAGE -> R.string.csv_key_storage
        CsvExport.Column.ADDED -> R.string.csv_added
    }
}
