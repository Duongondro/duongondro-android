package app.duongondro.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLayoutTest {
    /** Every character half an em wide. */
    private val measure = object : TextMeasure {
        override fun width(text: String, style: ReportStyle) = text.length * style.size * 0.5f
    }
    private val layout = ReportLayout(measure)

    private fun report(vararg blocks: ReportBlock) =
        Report("Duongöndro: your data", "Exported today", blocks.toList(), "duongondro.app/privacy") { p, n -> "Page $p of $n" }

    private fun texts(page: ReportPage) = page.draws.filterIsInstance<Draw.Text>().map { it.text }

    private val dayTable = listOf(Column("Day", 1f), Column("Count", 1f, right = true, figure = true))

    @Test fun headerAndFooterOnEveryPage() {
        val rows = (1..200).map { Row(listOf(Cell("Day $it"), Cell("$it"))) }
        val pages = layout.pages(report(ReportBlock.Heading("3. Sessions"), ReportBlock.Table(dayTable, rows, continued = "Dorje Sempa (continued)")))
        assertTrue(pages.size > 2)
        pages.forEachIndexed { i, page ->
            val t = texts(page)
            assertEquals("Duongöndro: your data", t.first())
            assertTrue("Exported today" in t)
            assertTrue("duongondro.app/privacy" in t)
            assertTrue("Page ${i + 1} of ${pages.size}" in t)
            // The table's header band repeats after a break.
            assertTrue("Day" in t)
            assertTrue(page.draws.any { it is Draw.Rect })
            assertEquals(i > 0, "Dorje Sempa (continued)" in t)
        }
        assertEquals(200, pages.sumOf { p -> texts(p).count { it.startsWith("Day ") } })
    }

    @Test fun contentStaysAboveTheFooter() {
        val rows = (1..120).map { Row(listOf(Cell("x"), Cell("1"))) }
        for (page in layout.pages(report(ReportBlock.Table(dayTable, rows)))) {
            val body = page.draws.filterIsInstance<Draw.Text>().filter { it.y > ReportLayout.CONTENT_TOP && it.y < ReportLayout.PAGE_HEIGHT - 45f }
            assertTrue(body.all { it.y <= ReportLayout.CONTENT_BOTTOM })
        }
    }

    @Test fun keepsADayTogetherWhereItFits() {
        val rows = (1..40).flatMap { d -> (1..5).map { r -> Row(listOf(Cell(if (r == 1) "Day $d" else ""), Cell("$r")), group = d) } }
        val pages = layout.pages(report(ReportBlock.Table(dayTable, rows)))
        assertTrue(pages.size > 1)
        for (page in pages) {
            val counts = page.draws.filterIsInstance<Draw.Text>().filter { it.style == ReportStyle.FIGURE }.map { it.text }
            assertEquals("a page holds whole days", 0, counts.size % 5)
            assertEquals("1", counts.first())
        }
    }

    @Test fun headingNeverEndsAPage() {
        val filler = (1..60).map { ReportBlock.Pair("Label $it", "Value") }
        val pages = layout.pages(report(*(filler + ReportBlock.Heading("2. Practices") + ReportBlock.Pair("a", "b")).toTypedArray()))
        val page = pages.first { "2. Practices" in texts(it) }
        assertTrue("b" in texts(page))
    }

    @Test fun secondLineIsSmallAndMuted() {
        val pages = layout.pages(report(ReportBlock.Table(listOf(Column("Practice", 1f)), listOf(Row(listOf(Cell("Dorje Sempa", "Diamond Mind")))))))
        val draws = pages.single().draws.filterIsInstance<Draw.Text>()
        val first = draws.first { it.text == "Dorje Sempa" }
        val second = draws.first { it.text == "Diamond Mind" }
        assertEquals(ReportStyle.BODY, first.style)
        assertEquals(ReportStyle.SMALL, second.style)
        assertTrue(second.y > first.y)
    }

    @Test fun rightColumnsEndAtTheirEdge() {
        val pages = layout.pages(report(ReportBlock.Table(dayTable, listOf(Row(listOf(Cell("a"), Cell("111,111")))))))
        val figure = pages.single().draws.filterIsInstance<Draw.Text>().first { it.text == "111,111" }
        assertTrue(figure.alignEnd)
        assertEquals(ReportStyle.FIGURE, figure.style)
    }

    @Test fun pairsWrapAndBreakLines() {
        val pages = layout.pages(report(ReportBlock.Pair("Devices", "2 devices\nHardware key")))
        val t = texts(pages.single())
        assertTrue("2 devices" in t && "Hardware key" in t)
    }

    @Test fun bulletsAndParagraphs() {
        val pages = layout.pages(report(ReportBlock.Heading("5. What the server keeps"), ReportBlock.Paragraph("Stored", ink = true, keep = true),
            ReportBlock.Paragraph("That your account exists", bullet = true)))
        val t = texts(pages.single())
        assertTrue(listOf("5. What the server keeps", "Stored", "•", "That your account exists").all { it in t })
    }

    @Test fun wrapsAndBreaksLongWords() {
        assertEquals(listOf("aaaa", "bbbb"), layout.wrap("aaaa bbbb", ReportStyle.BODY, 20f))
        assertEquals(listOf("aaaa", "aaaa"), layout.wrap("aaaaaaaa", ReportStyle.BODY, 20f))
    }

    @Test fun noBlankLastPage() {
        val filler = (1..48).map { ReportBlock.Pair("Label $it", "Value") }
        val pages = layout.pages(report(*(filler + ReportBlock.Gap(18f) + ReportBlock.Gap(18f)).toTypedArray()))
        assertTrue(texts(pages.last()).any { it.startsWith("Label") })
    }

    @Test fun aLongHeadingWrapsBeforeItsDetail() {
        val title = "3. Sessions · " + "Meditation on the Buddha of Limitless Light ".repeat(3)
        val draws = layout.pages(report(ReportBlock.Heading(title.trim(), "1,234 sessions"))).single().draws.filterIsInstance<Draw.Text>()
        val detail = draws.first { it.text == "1,234 sessions" }
        val detailStart = detail.x - measure.width(detail.text, ReportStyle.SMALL)
        draws.filter { it.style == ReportStyle.SECTION }.forEach { assertTrue(it.x + measure.width(it.text, ReportStyle.SECTION) < detailStart) }
    }
}
