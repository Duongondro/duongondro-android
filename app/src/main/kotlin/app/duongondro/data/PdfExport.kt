package app.duongondro.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.format.DateFormat
import androidx.core.content.res.ResourcesCompat
import app.duongondro.R
import app.duongondro.core.export.Draw
import app.duongondro.core.export.PdfText
import app.duongondro.core.export.Report
import app.duongondro.core.export.ReportFont
import app.duongondro.core.export.ReportLayout
import app.duongondro.core.export.ReportStyle
import app.duongondro.core.export.TextMeasure
import java.io.OutputStream
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The PDF export drawn with the platform's own `PdfDocument`, as CodeShare's: A4 in
 * points, IBM Plex Sans for headings and figures and the system sans for prose, all
 * with tabular figures, in the light theme for print. Core's [ReportLayout] decides
 * where everything goes, measuring with these paints; this only draws it.
 */
class PdfExport(private val context: Context) {
    private val paints = ReportStyle.entries.associateWith(::paint)

    /** Subpixel positions, linear metrics and no hinting: glyphs where the font puts them, as a PDF wants. */
    private fun paint(style: ReportStyle) = Paint().apply {
        isAntiAlias = true
        isSubpixelText = true
        isLinearText = true
        hinting = Paint.HINTING_OFF
        textSize = style.size
        color = style.color.argb.toInt()
        typeface = typeface(style.font)
        fontFeatureSettings = "'tnum'"
    }

    private fun typeface(font: ReportFont): Typeface = when (font) {
        ReportFont.PLEX_BOLD -> ResourcesCompat.getFont(context, R.font.ibm_plex_sans_bold)
        ReportFont.PLEX_SEMIBOLD -> ResourcesCompat.getFont(context, R.font.ibm_plex_sans_semibold)
        ReportFont.SYSTEM -> null
    } ?: Typeface.SANS_SERIF

    /** Lays [report] out and writes it to [out]; returns the number of pages. */
    fun write(report: Report, out: OutputStream): Int {
        val measure = object : TextMeasure {
            override fun width(text: String, style: ReportStyle) = paints.getValue(style).measureText(text)
            override fun ascent(style: ReportStyle) = -paints.getValue(style).fontMetrics.ascent
            override fun lineHeight(style: ReportStyle) = paints.getValue(style).fontMetrics.let { it.descent - it.ascent } * 1.12f
        }
        val pages = ReportLayout(measure).pages(report)
        val document = PdfDocument()
        try {
            for (page in pages) {
                val info = PdfDocument.PageInfo.Builder(ReportLayout.PAGE_WIDTH.toInt(), ReportLayout.PAGE_HEIGHT.toInt(), page.number).create()
                val pdfPage = document.startPage(info)
                val canvas = pdfPage.canvas
                for (draw in page.draws) {
                    when (draw) {
                        is Draw.Line -> canvas.drawLine(draw.x1, draw.y1, draw.x2, draw.y2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = draw.color.argb.toInt(); style = Paint.Style.STROKE; strokeWidth = draw.thickness
                        })
                        is Draw.Text -> {
                            val paint = paints.getValue(draw.style)
                            val x = if (draw.alignEnd) draw.x - paint.measureText(draw.text) else draw.x
                            canvas.drawText(draw.text, x, draw.y, paint)
                        }
                    }
                }
                document.finishPage(pdfPage)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return pages.size
    }

    companion object {
        /**
         * The export's words from the app's resources, in the app's language ([context]
         * carries it), with the locale's number and date formats. [version] is
         * "0.4 (12) · 3f9c2e1", what Settings shows.
         */
        fun text(context: Context, version: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): PdfText {
            val r = context.resources
            val locale: Locale = r.configuration.locales[0]
            val numbers = NumberFormat.getIntegerInstance(locale)
            fun n(value: Int) = numbers.format(value)
            val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            val day = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMMyyyy"), locale)
            val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            fun s(id: Int, vararg args: Any) = context.getString(id, *args)
            fun p(id: Int, count: Int) = r.getQuantityString(id, count, n(count))
            return PdfText(
                title = s(R.string.pdf_title),
                meta = s(R.string.pdf_exported, dateTime.format(now.atZone(zone))) + " · " + s(R.string.pdf_build, version),
                footer = s(R.string.pdf_footer),
                page = { page, of -> s(R.string.pdf_page, n(page), n(of)) },
                number = ::n,
                date = { date.format(it.atZone(zone)) },
                day = { day.format(it) },
                time = { at, z -> time.format(at.atZone(z)) },
                none = s(R.string.pdf_none), yes = s(R.string.pdf_yes), no = s(R.string.pdf_no),
                accountTitle = s(R.string.pdf_account), displayName = s(R.string.pdf_display_name), username = s(R.string.pdf_username),
                gender = s(R.string.pdf_gender), email = s(R.string.pdf_email), accountCreated = s(R.string.pdf_account_created),
                passkeys = s(R.string.pdf_passkeys), devices = s(R.string.pdf_devices), notGiven = s(R.string.pdf_not_given),
                localMode = s(R.string.pdf_local_mode), serverMissing = s(R.string.pdf_server_missing),
                genderName = { g ->
                    when (g) {
                        "male" -> s(R.string.gender_male)
                        "female" -> s(R.string.gender_female)
                        "nonbinary" -> s(R.string.gender_nonbinary)
                        else -> g
                    }
                },
                passkeyValue = { created, used -> if (used == null) s(R.string.pdf_passkey_added, created) else s(R.string.pdf_passkey_used, created, used) },
                deviceValue = { tier, added ->
                    val name = when (tier) {
                        "hardware" -> s(R.string.pdf_tier_hardware)
                        "tee" -> s(R.string.pdf_tier_tee)
                        "software" -> s(R.string.pdf_tier_software)
                        else -> tier
                    }
                    s(R.string.pdf_device, name, added)
                },
                practicesTitle = s(R.string.pdf_practices), practicesCount = { p(R.plurals.pdf_practices_count, it) },
                practiceColumn = s(R.string.pdf_practice), totalColumn = s(R.string.pdf_total), targetColumn = s(R.string.pdf_target),
                roundColumn = s(R.string.pdf_round), streakColumn = s(R.string.pdf_streak), publicColumn = s(R.string.pdf_public),
                archived = s(R.string.pdf_archived),
                streakValue = { current, longest -> r.getQuantityString(R.plurals.pdf_streak_value, current, n(current), n(longest)) },
                sessionsTitle = s(R.string.pdf_sessions), sessionsCount = { p(R.plurals.pdf_sessions_count, it) },
                dayColumn = s(R.string.pdf_day), startedColumn = s(R.string.pdf_started), countColumn = s(R.string.pdf_count),
                countedForColumn = s(R.string.pdf_counted_for), done = s(R.string.pdf_done), noSessions = s(R.string.pdf_no_sessions),
                friendsTitle = s(R.string.pdf_friends), friendsCount = { p(R.plurals.pdf_friends_count, it) },
                friendColumn = s(R.string.pdf_friend), sinceColumn = s(R.string.pdf_since), noFriends = s(R.string.pdf_no_friends),
                serverTitle = s(R.string.pdf_server),
                serverParagraphs = listOf(s(R.string.pdf_server_sealed), s(R.string.pdf_server_encrypted), s(R.string.pdf_server_plain)),
            )
        }
    }
}
