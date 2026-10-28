package app.duongondro.core.export

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.absoluteValue

/**
 * The words and formats of the PDF export in the app's language (iOS's Export.xcstrings,
 * shared through strings_export.xml): the app fills this from its resources, with plural
 * forms and the locale's number and date formats.
 */
class PdfText(
    val title: String,
    /** "Exported 15 Oct 2026, 21:14 · Android 0.4 (12) · 3f9c2e1". */
    val meta: String,
    val footer: String,
    val page: (page: Int, of: Int) -> String,
    val number: (Int) -> String,
    /** A date, with the time when asked, in the phone's zone; "–" for none. */
    val date: (Instant?, Boolean) -> String,
    /** A day with its weekday, as it fell in a zone: "Tue, 13 Oct 2026". */
    val day: (LocalDate, ZoneId) -> String,
    /** A time of day in a zone: "06:40". */
    val time: (Instant, ZoneId) -> String,
    /** Each key of iOS's Export.xcstrings this document uses, by its English text. */
    val word: (String) -> String,
    /** Plural keys ("%lld days") with their count. */
    val plural: (String, Int) -> String,
    val genderName: (String?) -> String,
    val tierName: (String) -> String,
    /** "Added %@" and "Added %@, last used %@". */
    val added: (String, String?) -> String,
    /** "Dorje Sempa (continued)", over a table's header repeated after a page break. */
    val continued: (String) -> String,
)

/**
 * The PDF export's document, block for block as iOS's ExportPDF builds it: 1. Account,
 * 2. Practices, 3. every session by practice and then by day, 4. Friends, 5. What the
 * server keeps (the privacy page's three lists).
 */
object PdfReport {
    private const val DASH = "–"

    fun build(content: ExportContent, t: PdfText): Report {
        val b = mutableListOf<ReportBlock>()
        fun l(key: String) = t.word(key)

        // 1. Account
        b += ReportBlock.Heading("1. " + l("Account"))
        val a = content.account
        if (a != null) {
            if (!content.serverIncluded) {
                b += ReportBlock.Paragraph(l(SERVER_UNREACHABLE), keep = true)
            }
            b += ReportBlock.Pair(l("Display name"), a.displayName?.takeIf { it.isNotEmpty() } ?: l("A friend"))
            b += ReportBlock.Pair(l("Username"), a.username ?: DASH)
            b += ReportBlock.Pair(l("Gender"), t.genderName(a.gender))
            b += ReportBlock.Pair(l("E-mail"), a.emails.ifEmpty { listOf(DASH) }.joinToString("\n"))
            if (content.serverIncluded) {
                b += ReportBlock.Pair(l("Account created"), t.date(a.createdAt, true))
                val passkeys = content.passkeys.map { t.added(t.date(it.createdAt, false), it.lastUsedAt?.let { u -> t.date(u, false) }) }
                b += ReportBlock.Pair(l("Passkeys"), (listOf(t.plural("%lld passkeys", content.passkeys.size)) + passkeys).joinToString("\n"))
                val devices = content.devices.map { "${t.tierName(it.tier)} · " + t.added(t.date(it.addedAt, false), null) }
                b += ReportBlock.Pair(l("Devices"), (listOf(t.plural("%lld devices", content.devices.size)) + devices).joinToString("\n"))
            }
        } else {
            b += ReportBlock.Paragraph(l("No online account: everything below is on this phone only."), ink = true)
        }
        b += ReportBlock.Gap(18f)

        // 2. Practices
        b += ReportBlock.Heading("2. " + l("Practices"), t.plural("%lld practices", content.practices.size))
        b += ReportBlock.Table(listOf(
            Column(l("Practice"), 2.4f),
            Column(l("Total"), 1.1f, right = true, figure = true),
            Column(l("Target"), 1.0f, right = true, figure = true),
            Column(l("Round"), 0.8f, right = true, figure = true),
            Column(l("Streak"), 1.0f, right = true, figure = true),
            Column(l("Longest"), 1.1f, right = true, figure = true),
            Column(l("Public"), 1.0f, right = true),
        ), continued = t.continued(l("Practices")), rows = content.practices.map { p ->
            val second = listOfNotNull(p.secondName, l("Archived").takeIf { p.archived }).joinToString(" · ").ifEmpty { null }
            val counted = !p.streakOnly
            Row(listOf(
                Cell(p.name, second),
                Cell(if (counted) t.number(p.total) else l("Streak only")),
                Cell(if (counted) p.target?.let(t.number) ?: DASH else DASH),
                Cell(p.round?.let(t.number) ?: DASH, p.inRound?.let(t.number)),
                Cell(t.plural("%lld days", p.currentStreak)),
                Cell(t.plural("%lld days", p.longestStreak)),
                Cell(if (p.public) l("Yes") else l("No")),
            ))
        })

        // 3. Sessions, by practice and then by the day they started on
        var group = 0
        val byPractice = content.sessions.groupBy { it.practiceId }
        for (p in content.practices) {
            val sessions = byPractice[p.id] ?: continue
            b += ReportBlock.Heading("3. " + l("Sessions") + " · " + p.name, t.plural("%lld sessions", sessions.size))
            val rows = mutableListOf<Row>()
            for ((day, list) in sessions.groupBy { it.startDay }.toSortedMap()) {
                group++
                list.forEachIndexed { k, x ->
                    rows += Row(listOf(
                        Cell(if (k == 0) t.day(day, x.zone) else ""),
                        Cell(t.time(x.startedAt, x.zone)),
                        Cell(when {
                            // A true minus sign, as History shows it, never the format's hyphen.
                            x.correction -> (if (x.count > 0) "+" else if (x.count < 0) "\u2212" else "") + t.number(x.count.absoluteValue)
                            p.streakOnly || x.count == 0 -> l("Done")
                            else -> t.number(x.count)
                        }),
                        Cell(x.round?.let(t.number) ?: DASH),
                        // A correction counts for no day: the column says what it is instead.
                        Cell(if (x.correction) l("Correction") else if (x.day != x.startDay) t.day(x.day, x.zone) else ""),
                    ), group)
                }
            }
            b += ReportBlock.Table(listOf(
                Column(l("Day"), 1.6f),
                Column(l("Started"), 0.9f),
                Column(l("Count"), 0.9f, right = true, figure = true),
                Column(l("Round"), 0.7f, right = true, figure = true),
                Column(l("Counted for"), 1.6f),
            ), rows, t.continued(p.name))
        }
        if (content.sessions.isEmpty()) {
            b += ReportBlock.Heading("3. " + l("Sessions"))
            b += ReportBlock.Paragraph(l("No sessions yet."))
            b += ReportBlock.Gap(18f)
        }
        if (content.unreadable > 0) {
            b += ReportBlock.Paragraph(t.plural(UNREADABLE, content.unreadable))
            b += ReportBlock.Gap(18f)
        }

        // 4. Friends
        b += ReportBlock.Heading("4. " + l("Friends"), if (a == null) null else t.plural("%lld friends", content.friends.size))
        when {
            a == null -> {
                b += ReportBlock.Paragraph(l("No online account"))
                b += ReportBlock.Gap(18f)
            }
            content.friends.isEmpty() -> {
                b += ReportBlock.Paragraph(l("No friends yet."))
                b += ReportBlock.Gap(18f)
            }
            else -> b += ReportBlock.Table(listOf(Column(l("Name"), 2f), Column(l("Friends since"), 1f)),
                content.friends.map { Row(listOf(Cell(it.displayName.ifEmpty { l("A friend") }), Cell(t.date(it.since, false)))) },
                t.continued(l("Friends")))
        }

        // 5. What the server keeps (the privacy page's lists)
        b += ReportBlock.Heading("5. " + l("What the server keeps"))
        if (a == null) {
            b += ReportBlock.Paragraph(l("Nothing: this phone has no online account."), ink = true)
        } else {
            for (list in PRIVACY) {
                b += ReportBlock.Paragraph(l(list.title), ink = true, keep = true)
                list.intro?.let { b += ReportBlock.Paragraph(l(it), keep = true) }
                list.items.forEach { b += ReportBlock.Paragraph(l(it), bullet = true) }
                list.after.forEach { b += ReportBlock.Paragraph(l(it)) }
                b += ReportBlock.Gap(8f)
            }
        }
        return Report(t.title, t.meta, b, t.footer, t.page)
    }

    const val SERVER_UNREACHABLE = "This phone has an account, but the server could not be reached, so this section and Friends hold only what the phone knows. Export again when you are connected."
    const val UNREADABLE = "%lld sealed sessions could not be opened on this phone, so they are only in the JSON export."

    class PrivacyList(val title: String, val intro: String?, val items: List<String>, val after: List<String>)

    /**
     * The privacy page's three lists, word for word: duongondro-landing src/pages/privacy.astro,
     * sections "sealed", "encrypted" and "plain" (as in landing PR #7). Change them together.
     */
    val PRIVACY: List<PrivacyList> = listOf(
        PrivacyList(
            "What is end-to-end encrypted",
            "These are sealed on your device before they are uploaded. The server stores them and cannot read them:",
            listOf(
                "practice sessions, including the day, the duration and your notes",
                "counts, rounds and totals",
                "targets",
                "imported streak seeds",
            ),
            listOf("The keys that open them stay on your devices. The server never receives a plaintext count or a key, and signing in does not give it one."),
        ),
        PrivacyList(
            "What the server encrypts",
            "The server has to read these to do its job, for example to show your name to your friends or to send a notification. It keeps them encrypted in its database, with a key held outside the database, so a copy of the database or a backup does not reveal them:",
            listOf(
                "your display name, and your grammatical gender if you give it",
                "your e-mail address, if you sign in with one; an account with only a passkey has none",
                "your username, if you chose one; it names your passkey and friends do not see it",
                "the Apple or Google account your sign-in is linked to",
                "your streaks, only for practices where you chose to publish them",
                "the public keys of your account, your devices and your passkeys",
                "push notification tokens",
                "the text of any report you send",
            ),
            listOf("Unlike your practice data, these can be read by the running server while it works with them."),
        ),
        PrivacyList(
            "What the server stores in plain form",
            null,
            listOf(
                "that your account exists, as a random number",
                "which accounts are friends, and who invited, blocked, poked or reported whom, as links between those numbers",
                "for each friend, whether you asked to hear when they practise",
                "whether and when you set up a recovery key",
                "which kinds of sign-in, notification service and device key storage you use, for example a passkey on an Android phone",
                "for a streak you publish, when it runs out, so the server can remind you in time, and how many times it has been updated",
                "when each thing was written and last changed, and how large each sealed blob is; for a practice session, also when your phone logged it and last changed or deleted it, by the phone's clock, even if it synced later",
            ),
            listOf(
                "The last item is a real limit. These times and sizes can show when you practise, even for a private streak.",
                "Cover photos for practices never leave your phone.",
            ),
        ),
    )
}
