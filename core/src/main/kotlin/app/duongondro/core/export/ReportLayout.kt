package app.duongondro.core.export

/**
 * The PDF export as a document (design: Data export and deletion; the mockup
 * "Export: PDF, page 1"): a header on every page with the title, the export
 * time and the build, then numbered sections of facts, tables and text, and a
 * footer with duongondro.app/privacy and "Page n of N". Every string is final
 * (translated and formatted by the caller); [ReportLayout] places it on A4
 * pages and the app draws the result with the platform's PDF API. Pure, so the
 * layout is tested here. Taken from CodeShare's export (core/export/ReportLayout.kt).
 */
data class Report(
    /** "Duongöndro: your data". */
    val title: String,
    /** "Exported 15 Oct 2026, 21:14 · Android 0.4 (3f9c2e1)". */
    val meta: String,
    val sections: List<ReportSection>,
    /** "duongondro.app/privacy". */
    val footer: String,
    val pageLabel: (page: Int, of: Int) -> String,
)

sealed class ReportSection {
    abstract val title: String

    /** Label and value pairs ("Display name   Tomasz"); a value wraps. */
    data class Facts(override val title: String, val pairs: List<Pair<String, String>>) : ReportSection()

    /**
     * A table under a heading with an optional right-aligned [detail] ("41 sessions"). Rows
     * come in [groups] (a day's sessions) kept on one page where a group fits on one; a
     * row as tall as its tallest cell. A cell's text after a line break is its second
     * line, muted (a practice's English name). The header row repeats after a page
     * break, and a group split by one repeats its first cell. [empty] stands in for no rows.
     */
    data class Table(
        override val title: String,
        val detail: String?,
        val columns: List<Column>,
        val groups: List<List<List<String>>>,
        val empty: String? = null,
    ) : ReportSection()

    /** Paragraphs, wrapped. */
    data class Text(override val title: String, val paragraphs: List<String>) : ReportSection()
}

/** A table column: its share of the width, and whether it holds figures (IBM Plex Sans, right-aligned). */
data class Column(val header: String, val weight: Float, val figures: Boolean = false)

/** IBM Plex Sans (the app bundles SemiBold and Bold) for headings and figures; the system sans for prose. */
enum class ReportFont { PLEX_BOLD, PLEX_SEMIBOLD, SYSTEM }

/** The light theme's type, for print; every style has tabular figures. */
enum class ReportStyle(val size: Float, val font: ReportFont, val color: ReportColor) {
    TITLE(20f, ReportFont.PLEX_BOLD, ReportColor.ACCENT),
    META(9f, ReportFont.SYSTEM, ReportColor.MUTED),
    HEADING(13f, ReportFont.PLEX_BOLD, ReportColor.INK),
    DETAIL(9f, ReportFont.SYSTEM, ReportColor.MUTED),
    LABEL(10f, ReportFont.SYSTEM, ReportColor.MUTED),
    VALUE(10f, ReportFont.SYSTEM, ReportColor.INK),
    TABLE_HEADER(9f, ReportFont.PLEX_SEMIBOLD, ReportColor.MUTED),
    CELL(10f, ReportFont.SYSTEM, ReportColor.INK),
    CELL_SECOND(9f, ReportFont.SYSTEM, ReportColor.MUTED),
    FIGURE(10f, ReportFont.PLEX_SEMIBOLD, ReportColor.INK),
    BODY(10f, ReportFont.SYSTEM, ReportColor.INK),
    FOOTER(8.5f, ReportFont.SYSTEM, ReportColor.MUTED),
}

/** The app's light theme tokens (ui/theme/Theme.kt), as ARGB: burgundy accent, warm ink, muted text, hairlines. */
enum class ReportColor(val argb: Long) {
    INK(0xFF22151A),
    MUTED(0xFF6B5A60),
    ACCENT(0xFF7A1F2E),
    RULE(0xFFE4DADD),
}

/** The platform's font metrics, in points. */
interface TextMeasure {
    fun width(text: String, style: ReportStyle): Float

    /** From a line's top to its baseline. */
    fun ascent(style: ReportStyle): Float = style.size * 0.96f

    /** One line's height. */
    fun lineHeight(style: ReportStyle): Float = style.size * 1.3f
}

/** One thing to draw. y of a [Draw.Text] is its baseline. */
sealed class Draw {
    data class Text(val x: Float, val y: Float, val text: String, val style: ReportStyle, val alignEnd: Boolean = false) : Draw()
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val thickness: Float, val color: ReportColor) : Draw()
}

data class ReportPage(val number: Int, val draws: List<Draw>)

/**
 * Places a [Report] on A4 pages: on every page the title and the meta line over a
 * burgundy rule, and the footer with "Page n of N" (known once everything is
 * placed); sections apart by [SECTION_GAP]; a row that does not fit above the footer
 * goes to the next page under its table's header row again.
 */
class ReportLayout(private val measure: TextMeasure) {
    private var pages = mutableListOf<MutableList<Draw>>()
    private var y = 0f
    private var contentTop = 0f
    private lateinit var report: Report

    fun pages(report: Report): List<ReportPage> {
        this.report = report
        pages = mutableListOf()
        beginPage()
        report.sections.forEachIndexed { index, section ->
            if (index > 0) y += SECTION_GAP
            when (section) {
                is ReportSection.Facts -> facts(section)
                is ReportSection.Table -> table(section)
                is ReportSection.Text -> paragraphs(section)
            }
        }
        val count = pages.size
        return pages.mapIndexed { index, draws ->
            val rule = PAGE_HEIGHT - BOTTOM - measure.lineHeight(ReportStyle.FOOTER) - 6f
            draws += Draw.Line(SIDE, rule, PAGE_WIDTH - SIDE, rule, HAIRLINE, ReportColor.RULE)
            val baseline = rule + 6f + measure.ascent(ReportStyle.FOOTER)
            draws += Draw.Text(SIDE, baseline, report.footer, ReportStyle.FOOTER)
            draws += Draw.Text(PAGE_WIDTH - SIDE, baseline, report.pageLabel(index + 1, count), ReportStyle.FOOTER, alignEnd = true)
            ReportPage(index + 1, draws.toList())
        }
    }

    private val draws: MutableList<Draw> get() = pages.last()

    private fun beginPage() {
        pages += mutableListOf<Draw>()
        y = TOP
        put(report.title, ReportStyle.TITLE, SIDE, y)
        y += measure.lineHeight(ReportStyle.TITLE)
        put(report.meta, ReportStyle.META, SIDE, y)
        y += measure.lineHeight(ReportStyle.META) + 6f
        draws += Draw.Line(SIDE, y, PAGE_WIDTH - SIDE, y, 2f, ReportColor.ACCENT)
        y += 16f
        contentTop = y
    }

    /** Below this, a new row or line goes to the next page. */
    private val limit get() = PAGE_HEIGHT - BOTTOM - measure.lineHeight(ReportStyle.FOOTER) - 16f

    private fun heading(title: String, detail: String?, following: Float) {
        val height = measure.lineHeight(ReportStyle.HEADING)
        if (y + height + 6f + following > limit && y > contentTop) beginPage()
        put(ellipsized(title, ReportStyle.HEADING, CONTENT_WIDTH - (detail?.let { measure.width(it, ReportStyle.DETAIL) + 12f } ?: 0f)),
            ReportStyle.HEADING, SIDE, y)
        if (detail != null) put(detail, ReportStyle.DETAIL, PAGE_WIDTH - SIDE, y + 3f, alignEnd = true)
        y += height + 6f
    }

    private fun facts(section: ReportSection.Facts) {
        val line = measure.lineHeight(ReportStyle.VALUE)
        heading(section.title, null, line)
        for ((label, value) in section.pairs) {
            val lines = wrap(value, ReportStyle.VALUE, CONTENT_WIDTH - FACT_VALUE_X)
            if (y + line > limit) beginPage()
            put(ellipsized(label, ReportStyle.LABEL, FACT_VALUE_X - 8f), ReportStyle.LABEL, SIDE, y)
            for (text in lines) {
                if (y + line > limit) beginPage()
                put(text, ReportStyle.VALUE, SIDE + FACT_VALUE_X, y)
                y += line
            }
            y += 3f
        }
        y -= 3f
    }

    private class Cell(val lines: List<Pair<String, ReportStyle>>)

    private fun table(section: ReportSection.Table) {
        val total = section.columns.sumOf { it.weight.toDouble() }.toFloat()
        val widths = section.columns.map { CONTENT_WIDTH * it.weight / total }
        val starts = widths.runningFold(SIDE) { x, w -> x + w }
        fun cells(values: List<String>) = section.columns.mapIndexed { i, column ->
            val parts = values.getOrElse(i) { "" }.split('\n')
            val width = widths[i] - 2 * CELL_PAD
            Cell(parts.flatMapIndexed { n, part ->
                val style = when {
                    n > 0 -> ReportStyle.CELL_SECOND
                    column.figures -> ReportStyle.FIGURE
                    else -> ReportStyle.CELL
                }
                wrap(part, style, width).map { it to style }
            })
        }
        fun height(cells: List<Cell>) = cells.maxOf { c -> c.lines.sumOf { measure.lineHeight(it.second).toDouble() }.toFloat() } + ROW_PAD
        fun header() {
            section.columns.forEachIndexed { i, column ->
                val x = if (column.figures) starts[i] + widths[i] - CELL_PAD else starts[i] + CELL_PAD
                put(ellipsized(column.header, ReportStyle.TABLE_HEADER, widths[i] - 2 * CELL_PAD), ReportStyle.TABLE_HEADER, x, y + 3f,
                    alignEnd = column.figures)
            }
            y += HEADER_ROW
            draws += Draw.Line(SIDE, y, PAGE_WIDTH - SIDE, y, HAIRLINE, ReportColor.RULE)
            y += 2f
        }
        fun row(cells: List<Cell>, h: Float) {
            section.columns.forEachIndexed { i, column ->
                var top = y + ROW_PAD / 2
                for ((text, style) in cells[i].lines) {
                    val x = if (column.figures) starts[i] + widths[i] - CELL_PAD else starts[i] + CELL_PAD
                    put(text, style, x, top, alignEnd = column.figures)
                    top += measure.lineHeight(style)
                }
            }
            y += h
        }

        val laidOut = section.groups.map { group -> group.map { values -> cells(values).let { it to height(it) } } }
        val first = laidOut.firstOrNull()?.firstOrNull()?.second ?: measure.lineHeight(ReportStyle.BODY)
        heading(section.title, section.detail, HEADER_ROW + 2f + first)
        if (laidOut.isEmpty()) {
            section.empty?.let {
                for (line in wrap(it, ReportStyle.BODY, CONTENT_WIDTH)) {
                    put(line, ReportStyle.BODY, SIDE, y)
                    y += measure.lineHeight(ReportStyle.BODY)
                }
            }
            return
        }
        header()
        val room = limit - contentTop - HEADER_ROW - 2f
        laidOut.forEachIndexed { g, group ->
            val groupHeight = group.sumOf { it.second.toDouble() }.toFloat()
            // Keep the group together where it fits on a page of its own.
            if (y + groupHeight > limit && groupHeight <= room) {
                beginPage()
                header()
            }
            group.forEachIndexed { r, (rowCells, h) ->
                var shown = rowCells
                var rowHeight = h
                if (y + h > limit) {
                    beginPage()
                    header()
                    if (r > 0) {
                        // A group split by a page break: its first cell again, so the day stays named.
                        val again = section.groups[g][r].toMutableList().also { it[0] = section.groups[g][0][0] }
                        shown = cells(again)
                        rowHeight = height(shown)
                    }
                }
                row(shown, rowHeight)
            }
            if (g < laidOut.lastIndex) draws += Draw.Line(SIDE, y, PAGE_WIDTH - SIDE, y, HAIRLINE, ReportColor.RULE)
        }
    }

    private fun paragraphs(section: ReportSection.Text) {
        val line = measure.lineHeight(ReportStyle.BODY)
        val wrapped = section.paragraphs.map { wrap(it, ReportStyle.BODY, CONTENT_WIDTH) }
        heading(section.title, null, line * minOf(wrapped.firstOrNull()?.size ?: 1, 2))
        wrapped.forEachIndexed { i, lines ->
            if (i > 0) y += PARAGRAPH_GAP
            for (text in lines) {
                if (y + line > limit) beginPage()
                put(text, ReportStyle.BODY, SIDE, y)
                y += line
            }
        }
    }

    /** Text whose line top is [top]. */
    private fun put(text: String, style: ReportStyle, x: Float, top: Float, alignEnd: Boolean = false) {
        if (text.isEmpty()) return
        draws += Draw.Text(x, top + measure.ascent(style), text, style, alignEnd)
    }

    /**
     * Words to lines no wider than [width]; a word wider than the line is broken
     * between characters, never cut, and never between the halves of a surrogate pair.
     */
    fun wrap(text: String, style: ReportStyle, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure.width(candidate, style) <= width) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) lines += current
            var rest = word
            while (rest.length > 1 && measure.width(rest, style) > width) {
                var end = rest.length - 1
                while (end > 1 && measure.width(rest.take(end), style) > width) end--
                if (end > 1 && Character.isLowSurrogate(rest[end])) end--
                if (end == 1 && Character.isHighSurrogate(rest[0]) && rest.length > 2) end = 2
                lines += rest.take(end)
                rest = rest.drop(end)
            }
            current = rest
        }
        if (current.isNotEmpty()) lines += current
        return lines.ifEmpty { listOf("") }
    }

    /** [text], cut with "…" when wider than [width]. */
    fun ellipsized(text: String, style: ReportStyle, width: Float): String {
        if (measure.width(text, style) <= width) return text
        var end = text.length
        while (end > 0 && measure.width(text.take(end).trimEnd() + "…", style) > width) end--
        return text.take(end).trimEnd() + "…"
    }

    companion object {
        /** A4 in whole points, as `PdfDocument` takes them (595.28 × 841.89). */
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val SIDE = 44f
        const val TOP = 40f
        const val BOTTOM = 28f
        const val CONTENT_WIDTH = PAGE_WIDTH - 2 * SIDE
        const val SECTION_GAP = 20f
        const val PARAGRAPH_GAP = 6f
        const val FACT_VALUE_X = 140f
        const val HEADER_ROW = 16f
        const val CELL_PAD = 4f
        const val ROW_PAD = 5f
        const val HAIRLINE = 0.5f
    }
}
