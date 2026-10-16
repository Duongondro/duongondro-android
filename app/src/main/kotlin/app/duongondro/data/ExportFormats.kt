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
    JSON("application/zip"),
    CSV("application/zip");

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
            practices = s.practices, sessions = s.sessions, seeds = s.seeds, publicPractices = s.publicPractices, defaultMalaSize = s.preferences.malaSize,
            account = account, server = server?.let { ServerAccount.parse(it.account) }, cachedFriends = cachedFriends,
            now = now, zone = zone, names = { it.shownNames(context) }, unreadable = (server?.unreadable ?: 0) + (server?.refused ?: 0),
        )
    }

    fun pdf(context: Context, content: ExportContent, version: String, now: Instant = Instant.now()): ByteArray {
        val out = ByteArrayOutputStream()
        PdfExport(context).write(PdfReport.build(content, PdfExport.text(context, version, now)), out)
        return out.toByteArray()
    }

    fun csv(context: Context, content: ExportContent, now: Instant = Instant.now()): ByteArray =
        CsvExport.zip(content, { context.getString(header(it)) }, now, aFriend = context.getString(R.string.ex_a_friend))

    /** Each CSV column's header in the app's language, as iOS words it. */
    fun header(column: CsvExport.Column): Int = ExportStrings.words.getValue(column.english)
}
