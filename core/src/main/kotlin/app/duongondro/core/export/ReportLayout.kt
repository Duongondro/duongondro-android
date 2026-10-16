package app.duongondro.core.export

/**
 * The PDF export as a document, drawn as iOS's ExportPDF (and CodeShare's) is: A4, a
 * header on every page with the title, the export time and the build over a burgundy
 * rule, a footer with duongondro.app/privacy and "Page n of N", and between them
 * headings, label and value pairs, paragraphs and tables, broken into pages by
 * [PageFlow]. Every string is final (translated and formatted by the caller);
 * [ReportLayout] measures and places it, and the app draws the result with the
 * platform's PDF API. Pure, so the layout is tested here.
 */
data class Report(
    /** "Duongöndro: your data". */
    val title: String,
    /** "Exported 15 Oct 2026, 21:14 · Android 0.4 (12) · 3f9c2e1". */
    val meta: String,
    val blocks: List<ReportBlock>,
    /** "duongondro.app/privacy". */
    val footer: String,
    val pageLabel: (page: Int, of: Int) -> String,
)

sealed class ReportBlock {
    /** A section's title, with an optional right-aligned [detail] ("41 sessions"); never ends a page. */
    data class Heading(val title: String, val detail: String? = null) : ReportBlock()

    /** "Display name   Tidy Raccoon"; the value may hold line breaks. */
    data class Pair(val label: String, val value: String) : ReportBlock()

    /** Wrapped text: muted unless [ink]; [keep] keeps it with what follows; [bullet] indents it under a "•". */
    data class Paragraph(val text: String, val ink: Boolean = false, val keep: Boolean = false, val bullet: Boolean = false) : ReportBlock()

    data class Gap(val height: Float) : ReportBlock()

    /**
     * A header band, then rows; rows of one group stay together where they fit. After a page
     * break the band repeats under [continued] ("Dorje Sempa (continued)").
     */
    data class Table(val columns: List<Column>, val rows: List<Row>, val continued: String? = null) : ReportBlock()
}

/** A table column: its share of the width, whether it aligns right, whether it holds figures (IBM Plex Sans). */
data class Column(val header: String, val weight: Float, val right: Boolean = false, val figure: Boolean = false)

data class Row(val cells: List<Cell>, val group: Int? = null)

/** One line, or a first line with a muted second (a practice's English name, the count within a round). */
data class Cell(val main: String, val second: String? = null)

/** IBM Plex Sans (the app bundles SemiBold and Bold) for headings and figures; the system sans for prose. */
enum class ReportFont { PLEX_BOLD, PLEX_SEMIBOLD, SYSTEM, SYSTEM_SEMIBOLD }

/** iOS's type for the export; Plex with tabular figures (the system sans goes without on Android, see PdfExport). */
enum class ReportStyle(val size: Float, val font: ReportFont, val color: ReportColor) {
    TITLE(22f, ReportFont.PLEX_BOLD, ReportColor.ACCENT),
    SECTION(13f, ReportFont.PLEX_SEMIBOLD, ReportColor.INK),
    FIGURE(9.5f, ReportFont.PLEX_SEMIBOLD, ReportColor.INK),
    BODY(9.5f, ReportFont.SYSTEM, ReportColor.INK),
    BODY_MUTED(9.5f, ReportFont.SYSTEM, ReportColor.MUTED),
    LABEL(8.5f, ReportFont.SYSTEM_SEMIBOLD, ReportColor.MUTED),
    SMALL(8f, ReportFont.SYSTEM, ReportColor.MUTED),
}

/** The app's light theme tokens (ui/theme/Theme.kt), as ARGB: a PDF is printed on white. */
enum class ReportColor(val argb: Long) {
    INK(0xFF22151A),
    MUTED(0xFF6B5A60),
    ACCENT(0xFF7A1F2E),
    LINE(0xFFEFE7E4),
    /** The table header band: the app's ground. */
    BAND(0xFFF7F3F1),
}

/** The platform's font metrics, in points. */
interface TextMeasure {
    fun width(text: String, style: ReportStyle): Float

    /** From a line's top to its baseline. */
    fun ascent(style: ReportStyle): Float = style.size * 0.96f

    /** One line's height, with iOS's 1.15 line-height multiple. */
    fun lineHeight(style: ReportStyle): Float = style.size * 1.2f * 1.15f
}

/** One thing to draw. y of a [Draw.Text] is its baseline; an [Draw.Text.alignEnd] text ends at x. */
sealed class Draw {
    data class Text(val x: Float, val y: Float, val text: String, val style: ReportStyle, val alignEnd: Boolean = false) : Draw()
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val thickness: Float, val color: ReportColor) : Draw()
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float, val color: ReportColor) : Draw()
}

data class ReportPage(val number: Int, val draws: List<Draw>)

private typealias Painter = MutableList<Draw>.(Float) -> Unit

class ReportLayout(private val measure: TextMeasure) {
    /** A block's height and how to draw it at a y. */
    private class Item(val block: PageFlow.Block, val draw: Painter)

    fun pages(report: Report): List<ReportPage> {
        val items = mutableListOf<Item>()
        val headers = mutableMapOf<Int, kotlin.Pair<Float, Painter>>()
        var tableId = 0
        for (block in report.blocks) when (block) {
            is ReportBlock.Heading -> heading(block, items)
            is ReportBlock.Pair -> pair(block, items)
            is ReportBlock.Paragraph -> paragraph(block, items)
            is ReportBlock.Gap -> items += Item(PageFlow.Block(block.height, gap = true)) {}
            is ReportBlock.Table -> table(tableId++, block, items, headers)
        }
        val flow = PageFlow.paginate(items.map { it.block }, CONTENT_BOTTOM - CONTENT_TOP, headers.mapValues { it.value.first })
        return flow.mapIndexed { n, placements ->
            val draws = mutableListOf<Draw>()
            frame(report, draws, n + 1, flow.size)
            for (p in placements) when (p) {
                is PageFlow.Placement.At -> items[p.index].draw(draws, CONTENT_TOP + p.y)
                is PageFlow.Placement.Header -> headers[p.table]?.second?.invoke(draws, CONTENT_TOP + p.y)
            }
            ReportPage(n + 1, draws)
        }
    }

    private fun frame(report: Report, draws: MutableList<Draw>, page: Int, total: Int) {
        var y = TOP
        y += draws.text(report.title, ReportStyle.TITLE, SIDE, y, CONTENT_WIDTH)
        y += draws.text(report.meta, ReportStyle.SMALL, SIDE, y, CONTENT_WIDTH)
        draws += Draw.Line(SIDE, y + 6f, PAGE_WIDTH - SIDE, y + 6f, 1.5f, ReportColor.ACCENT)
        val footer = PAGE_HEIGHT - 40f
        draws += Draw.Line(SIDE, footer - 6f, PAGE_WIDTH - SIDE, footer - 6f, HAIRLINE, ReportColor.LINE)
        draws.text(report.footer, ReportStyle.SMALL, SIDE, footer, CONTENT_WIDTH)
        draws.text(report.pageLabel(page, total), ReportStyle.SMALL, SIDE, footer, CONTENT_WIDTH, right = true)
    }

    private fun heading(h: ReportBlock.Heading, items: MutableList<Item>) {
        // The detail is measured first, so a long title wraps before it rather than running into it.
        val detailWidth = h.detail?.let { measure.width(it, ReportStyle.SMALL) + 12f } ?: 0f
        val titleWidth = CONTENT_WIDTH - detailWidth
        val height = height(h.title, ReportStyle.SECTION, titleWidth) + 8f
        items += Item(PageFlow.Block(height, keepWithNext = true)) { y ->
            text(h.title, ReportStyle.SECTION, SIDE, y, titleWidth)
            h.detail?.let { text(it, ReportStyle.SMALL, SIDE, y + 4f, CONTENT_WIDTH, right = true) }
        }
    }

    private fun pair(p: ReportBlock.Pair, items: MutableList<Item>) {
        val valueWidth = CONTENT_WIDTH - LABEL_WIDTH
        val height = maxOf(height(p.label, ReportStyle.BODY_MUTED, LABEL_WIDTH), height(p.value, ReportStyle.BODY, valueWidth)) + 4f
        items += Item(PageFlow.Block(height)) { y ->
            text(p.label, ReportStyle.BODY_MUTED, SIDE, y, LABEL_WIDTH)
            text(p.value, ReportStyle.BODY, SIDE + LABEL_WIDTH, y, valueWidth)
        }
    }

    private fun paragraph(p: ReportBlock.Paragraph, items: MutableList<Item>) {
        val indent = if (p.bullet) 12f else 0f
        val style = if (p.ink) ReportStyle.BODY else ReportStyle.BODY_MUTED
        val height = height(p.text, style, CONTENT_WIDTH - indent) + 4f
        items += Item(PageFlow.Block(height, keepWithNext = p.keep)) { y ->
            if (p.bullet) text("•", style, SIDE + 2f, y, indent)
            text(p.text, style, SIDE + indent, y, CONTENT_WIDTH - indent)
        }
    }

    private fun table(id: Int, t: ReportBlock.Table, items: MutableList<Item>, headers: MutableMap<Int, kotlin.Pair<Float, Painter>>) {
        val total = t.columns.sumOf { it.weight.toDouble() }.toFloat()
        val widths = t.columns.map { CONTENT_WIDTH * it.weight / total }
        val starts = widths.runningFold(SIDE) { x, w -> x + w }
        val headerHeight = t.columns.indices.maxOf { height(t.columns[it].header, ReportStyle.LABEL, widths[it] - 8f) } + 8f
        val header: Painter = { y ->
            this += Draw.Rect(SIDE, y, CONTENT_WIDTH, headerHeight, ReportColor.BAND)
            t.columns.forEachIndexed { i, c -> text(c.header, ReportStyle.LABEL, starts[i] + 4f, y + 4f, widths[i] - 8f, right = c.right) }
        }
        val continuedHeight = t.continued?.let { height(it, ReportStyle.BODY_MUTED, CONTENT_WIDTH) + 4f } ?: 0f
        headers[id] = (continuedHeight + headerHeight) to { y ->
            t.continued?.let { text(it, ReportStyle.BODY_MUTED, SIDE, y, CONTENT_WIDTH) }
            header(y + continuedHeight)
        }
        items += Item(PageFlow.Block(headerHeight, keepWithNext = true), header)
        fun styleOf(c: Column) = if (c.figure) ReportStyle.FIGURE else ReportStyle.BODY
        for (row in t.rows) {
            val rowHeight = t.columns.indices.maxOf { i ->
                val cell = row.cells.getOrElse(i) { Cell("") }
                height(cell.main, styleOf(t.columns[i]), widths[i] - 8f) +
                    (cell.second?.takeIf { it.isNotEmpty() }?.let { height(it, ReportStyle.SMALL, widths[i] - 8f) } ?: 0f)
            } + 8f
            items += Item(PageFlow.Block(rowHeight, table = id, group = row.group)) { y ->
                t.columns.forEachIndexed { i, c ->
                    val cell = row.cells.getOrElse(i) { Cell("") }
                    val w = widths[i] - 8f
                    val first = text(cell.main, styleOf(c), starts[i] + 4f, y + 4f, w, right = c.right)
                    cell.second?.takeIf { it.isNotEmpty() }?.let { text(it, ReportStyle.SMALL, starts[i] + 4f, y + 4f + first, w, right = c.right) }
                }
                this += Draw.Line(SIDE, y + rowHeight, PAGE_WIDTH - SIDE, y + rowHeight, HAIRLINE, ReportColor.LINE)
            }
        }
        items += Item(PageFlow.Block(18f, gap = true)) {}
    }

    /** The wrapped text's height in [width]. */
    private fun height(text: String, style: ReportStyle, width: Float): Float = lines(text, style, width).size * measure.lineHeight(style)

    private fun lines(text: String, style: ReportStyle, width: Float): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n').flatMap { wrap(it, style, width) }

    /** Draws [text] wrapped to [width] from line top [top]; returns its height. */
    private fun MutableList<Draw>.text(text: String, style: ReportStyle, x: Float, top: Float, width: Float, right: Boolean = false): Float {
        val lines = lines(text, style, width)
        lines.forEachIndexed { n, line ->
            if (line.isNotEmpty()) {
                this += Draw.Text(if (right) x + width else x, top + n * measure.lineHeight(style) + measure.ascent(style), line, style, right)
            }
        }
        return lines.size * measure.lineHeight(style)
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

    companion object {
        /** A4 in whole points, as `PdfDocument` takes them (595.28 × 841.89). */
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val SIDE = 44f
        const val TOP = 40f
        /** Where the content starts and ends on every page, as on iOS. */
        const val CONTENT_TOP = 100f
        const val CONTENT_BOTTOM = PAGE_HEIGHT - 52f
        const val CONTENT_WIDTH = PAGE_WIDTH - 2 * SIDE
        const val LABEL_WIDTH = 130f
        const val HAIRLINE = 0.5f
    }
}
