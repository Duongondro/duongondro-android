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
        ReportFont.SYSTEM_SEMIBOLD -> Typeface.create(Typeface.SANS_SERIF, 600, false)
    } ?: Typeface.SANS_SERIF

    /** Lays [report] out and writes it to [out]; returns the number of pages. */
    fun write(report: Report, out: OutputStream): Int {
        val measure = object : TextMeasure {
            override fun width(text: String, style: ReportStyle) = paints.getValue(style).measureText(text)
            override fun ascent(style: ReportStyle) = -paints.getValue(style).fontMetrics.ascent
            override fun lineHeight(style: ReportStyle) = paints.getValue(style).fontMetrics.let { it.descent - it.ascent } * 1.15f
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
                        is Draw.Rect -> canvas.drawRect(draw.x, draw.y, draw.x + draw.width, draw.y + draw.height, Paint().apply {
                            color = draw.color.argb.toInt(); style = Paint.Style.FILL
                        })
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
         * The export's words from the app's resources (iOS's Export.xcstrings, through
         * strings_export.xml), in the app's language ([context] carries it), with the locale's
         * number and date formats. [build] is "0.4 (12) · 3f9c2e1".
         */
        fun text(context: Context, build: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): PdfText {
            val r = context.resources
            val locale: Locale = r.configuration.locales[0]
            val numbers = NumberFormat.getIntegerInstance(locale)
            fun n(value: Int) = numbers.format(value)
            val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            val day = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMMyyyy"), locale)
            val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            fun word(key: String): String = context.getString(ExportStrings.words[key] ?: error("no string for \"$key\""))
            return PdfText(
                title = word("Duongöndro: your data"),
                meta = context.getString(R.string.ex_exported, dateTime.format(now.atZone(zone)), build),
                footer = context.getString(R.string.ex_footer_url),
                page = { page, of -> context.getString(R.string.ex_page_n_of_n, n(page), n(of)) },
                number = ::n,
                date = { at, withTime -> at?.let { (if (withTime) dateTime else date).format(it.atZone(zone)) } ?: context.getString(R.string.ex_dash) },
                day = { d, z -> day.format(d.atStartOfDay(z).plusHours(12)) },
                time = { at, z -> time.format(at.atZone(z)) },
                word = ::word,
                plural = { key, count -> r.getQuantityString(ExportStrings.plurals[key] ?: error("no plural for \"$key\""), count, n(count)) },
                genderName = { g ->
                    when (g) {
                        "male" -> word("Male")
                        "female" -> word("Female")
                        "nonbinary" -> word("Non-binary")
                        else -> word("Not given")
                    }
                },
                tierName = { tier ->
                    when (tier) {
                        "hardware" -> word("Hardware key")
                        "tee" -> word("Trusted execution environment key")
                        "software" -> word("Software key")
                        else -> tier
                    }
                },
                added = { created, used ->
                    if (used == null) context.getString(R.string.ex_added_x, created) else context.getString(R.string.ex_added_last_used, created, used)
                },
                continued = { context.getString(R.string.ex_continued, it) },
            )
        }
    }
}
