package app.duongondro.core.export

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The words and formats of the PDF export, in the app's language: the app fills
 * this from its resources (plural forms, locale number and date formatting).
 */
class PdfText(
    val title: String,
    /** "Exported 15 Oct 2026, 21:14 · Android 0.4 (3f9c2e1)". */
    val meta: String,
    val footer: String,
    val page: (page: Int, of: Int) -> String,
    val number: (Int) -> String,
    /** A date and time in the phone's zone: "15 Oct 2026". */
    val date: (Instant) -> String,
    /** A day with its weekday: "Tue, 13 Oct 2026". */
    val day: (LocalDate) -> String,
    /** A time of day where the session started: "06:40". */
    val time: (Instant, ZoneId) -> String,
    val none: String,
    val yes: String,
    val no: String,

    val accountTitle: String,
    val displayName: String,
    val username: String,
    val gender: String,
    val email: String,
    val accountCreated: String,
    val passkeys: String,
    val devices: String,
    val notGiven: String,
    val localMode: String,
    /** For passkeys and devices when the server's part could not be fetched. */
    val serverMissing: String,
    val genderName: (String) -> String,
    /** "Added 3 Oct 2026, last used 15 Oct 2026". */
    val passkeyValue: (created: String, lastUsed: String?) -> String,
    /** "Hardware key, added 3 Oct 2026". */
    val deviceValue: (tier: String, added: String) -> String,

    val practicesTitle: String,
    val practicesCount: (Int) -> String,
    val practiceColumn: String,
    val totalColumn: String,
    val targetColumn: String,
    val roundColumn: String,
    val streakColumn: String,
    val publicColumn: String,
    val archived: String,
    /** "12 days · longest 40". */
    val streakValue: (current: Int, longest: Int) -> String,

    /** "Sessions", before " · Dorje Sempa". */
    val sessionsTitle: String,
    val sessionsCount: (Int) -> String,
    val dayColumn: String,
    val startedColumn: String,
    val countColumn: String,
    val countedForColumn: String,
    /** A streak-only session's count: "done". */
    val done: String,
    val noSessions: String,

    val friendsTitle: String,
    val friendsCount: (Int) -> String,
    val friendColumn: String,
    val sinceColumn: String,
    val noFriends: String,

    val serverTitle: String,
    val serverParagraphs: List<String>,
)

/** The PDF export's document: 1. Account, 2. Practices, 3. Sessions per practice, 4. Friends, 5. What the server keeps. */
object PdfReport {
    fun build(content: ExportContent, t: PdfText): Report {
        val sections = mutableListOf<ReportSection>()
        var n = 0
        fun numbered(title: String) = "${++n}. $title"

        sections += ReportSection.Facts(numbered(t.accountTitle), account(content, t))

        val practiceColumns = listOf(
            Column(t.practiceColumn, 2.8f), Column(t.totalColumn, 1.2f, figures = true), Column(t.targetColumn, 1.3f, figures = true),
            Column(t.roundColumn, 0.8f, figures = true), Column(t.streakColumn, 2.5f), Column(t.publicColumn, 1.1f),
        )
        sections += ReportSection.Table(numbered(t.practicesTitle), t.practicesCount(content.practices.size), practiceColumns,
            content.practices.map { p ->
                val second = listOfNotNull(p.secondName, t.archived.takeIf { p.archived }).joinToString(" · ")
                listOf(listOf(
                    if (second.isEmpty()) p.name else "${p.name}\n$second",
                    if (p.streakOnly) t.none else t.number(p.total),
                    p.target?.takeIf { !p.streakOnly }?.let(t.number) ?: t.none,
                    p.round?.let(t.number) ?: t.none,
                    t.streakValue(p.currentStreak, p.longestStreak),
                    if (p.public) t.yes else t.no,
                ))
            }, t.none)

        val sessionsNumber = ++n
        val byPractice = content.sessions.groupBy { it.practiceId }
        val sessionColumns = listOf(
            Column(t.dayColumn, 2.6f), Column(t.startedColumn, 1.2f), Column(t.countColumn, 1.3f, figures = true),
            Column(t.roundColumn, 0.9f, figures = true), Column(t.countedForColumn, 2.6f),
        )
        val withSessions = content.practices.filter { byPractice.containsKey(it.id) }
        if (withSessions.isEmpty()) {
            sections += ReportSection.Text("$sessionsNumber. ${t.sessionsTitle}", listOf(t.noSessions))
        }
        for (p in withSessions) {
            val rows = byPractice.getValue(p.id)
            val groups = rows.groupBy { it.startDay }.toSortedMap().map { (day, list) ->
                list.mapIndexed { i, s ->
                    listOf(
                        if (i == 0) t.day(day) else "",
                        t.time(s.startedAt, s.zone),
                        if (p.streakOnly && s.count == 0) t.done else t.number(s.count),
                        s.round?.let(t.number) ?: t.none,
                        if (s.day != s.startDay) t.day(s.day) else "",
                    )
                }
            }
            sections += ReportSection.Table("$sessionsNumber. ${t.sessionsTitle} · ${p.name}", t.sessionsCount(rows.size), sessionColumns, groups)
        }

        sections += ReportSection.Table(numbered(t.friendsTitle), t.friendsCount(content.friends.size).takeIf { content.friends.isNotEmpty() },
            listOf(Column(t.friendColumn, 3f), Column(t.sinceColumn, 2f)),
            content.friends.map { f -> listOf(listOf(f.displayName.ifBlank { t.notGiven }, f.since?.let(t.date) ?: t.none)) },
            empty = when {
                content.account == null -> t.localMode
                !content.serverIncluded -> t.serverMissing
                else -> t.noFriends
            })

        sections += ReportSection.Text(numbered(t.serverTitle), t.serverParagraphs)

        return Report(t.title, t.meta, sections, t.footer, t.page)
    }

    private fun account(content: ExportContent, t: PdfText): List<Pair<String, String>> {
        val a = content.account ?: return listOf(t.accountTitle to t.localMode)
        val out = mutableListOf(
            t.displayName to (a.displayName ?: t.notGiven),
            t.username to (a.username ?: t.notGiven),
            t.gender to (a.gender?.let(t.genderName) ?: t.notGiven),
            t.email to a.emails.ifEmpty { listOf(t.notGiven) }.joinToString(", "),
        )
        a.createdAt?.let { out += t.accountCreated to t.date(it) }
        if (!content.serverIncluded) {
            out += t.passkeys to t.serverMissing
            out += t.devices to t.serverMissing
            return out
        }
        fun list(label: String, values: List<String>) {
            if (values.isEmpty()) out += label to t.none
            values.forEachIndexed { i, v -> out += (if (i == 0) label else "") to v }
        }
        list(t.passkeys, content.passkeys.map { t.passkeyValue(t.date(it.createdAt), it.lastUsedAt?.let(t.date)) })
        list(t.devices, content.devices.map { t.deviceValue(it.tier, t.date(it.addedAt)) })
        return out
    }
}
