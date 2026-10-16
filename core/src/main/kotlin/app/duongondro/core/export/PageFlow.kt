package app.duongondro.core.export

/**
 * Page breaking for the PDF export, as iOS's PageFlow (DuongondroStore): blocks of
 * measured height flow down pages; a heading never ends a page; a table repeats its
 * header after a page break; and rows of one group (a day's sessions) stay together
 * where they fit on a page. Gaps collapse at a page break and at the end, so no page
 * starts with space and none is left blank.
 */
object PageFlow {
    /**
     * @property keepWithNext stays on the page of the block after it (a heading, a table's header).
     * @property table the table this row belongs to, whose header repeats after a break.
     * @property group rows with the same group move to the next page together, where they fit on one.
     * @property gap space between blocks: dropped where it would end or start a page.
     */
    data class Block(
        val height: Float, val keepWithNext: Boolean = false, val table: Int? = null, val group: Int? = null, val gap: Boolean = false,
    )

    sealed class Placement {
        /** The block at this index, at [y] from the content top. */
        data class At(val index: Int, val y: Float) : Placement()

        /** The header of a table, repeated at the top of a continuation page. */
        data class Header(val table: Int, val y: Float) : Placement()
    }

    /** Pages of placements. [available] is the content height of a page; [headers] the heights of tables' repeated headers. */
    fun paginate(blocks: List<Block>, available: Float, headers: Map<Int, Float> = emptyMap()): List<List<Placement>> {
        val pages = mutableListOf(mutableListOf<Placement>())
        var y = 0f
        var i = 0

        fun newPage(continuing: Int?) {
            pages += mutableListOf<Placement>()
            y = 0f
            val h = continuing?.let { headers[it] } ?: return
            pages.last() += Placement.Header(continuing, 0f)
            y = h
        }

        fun hasBlock() = pages.last().any { it is Placement.At }

        while (i < blocks.size) {
            if (blocks[i].gap) {
                // Space only between blocks on one page: never at a page's top, never pushing onto a new one.
                if (hasBlock() && y + blocks[i].height <= available) {
                    pages.last() += Placement.At(i, y)
                    y += blocks[i].height
                }
                i++
                continue
            }
            // The unit that moves as one: a run of keep-with-next blocks (headings, a table's
            // header) with the block they keep to, and the rest of a row's group.
            var end = i
            while (end < blocks.size - 1 && blocks[end].keepWithNext) end++
            val head = (i..end).sumOf { blocks[it].height.toDouble() }.toFloat()
            val g = blocks[end].group
            val t = blocks[end].table
            if (g != null && t != null) {
                while (end + 1 < blocks.size && blocks[end + 1].group == g && blocks[end + 1].table == t) end++
            }
            val unit = (i..end).sumOf { blocks[it].height.toDouble() }.toFloat()
            // A unit that starts with a row continues its table on the next page.
            val continuing = if (blocks[i].keepWithNext) null else blocks[i].table
            val fresh = available - (continuing?.let { headers[it] } ?: 0f)
            if (hasBlock() && y + unit > available && (unit <= fresh || y + head > available)) newPage(continuing)
            // Block by block: a unit taller than a page breaks inside it.
            for (k in i..end) {
                if (y + blocks[k].height > available && hasBlock()) newPage(if (blocks[k].keepWithNext) null else blocks[k].table)
                pages.last() += Placement.At(k, y)
                y += blocks[k].height
            }
            i = end + 1
        }
        // Trailing gaps hold nothing; a page left with no block is dropped.
        return pages.filter { p -> p.any { it is Placement.At && !blocks[it.index].gap } }
    }
}
