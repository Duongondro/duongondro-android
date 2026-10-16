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

    private fun report(vararg sections: ReportSection) =
        Report("Duongöndro: your data", "Exported today", sections.toList(), "duongondro.app/privacy") { p, n -> "Page $p of $n" }

    private fun texts(page: ReportPage) = page.draws.filterIsInstance<Draw.Text>().map { it.text }

    @Test fun headerAndFooterOnEveryPage() {
        val rows = (1..200).map { listOf(listOf("Day $it", "$it")) }
        val pages = layout.pages(report(ReportSection.Table("Sessions", null, listOf(Column("Day", 1f), Column("Count", 1f, true)), rows)))
        assertTrue(pages.size > 2)
        pages.forEachIndexed { i, page ->
            val t = texts(page)
            assertEquals("Duongöndro: your data", t.first())
            assertTrue("Exported today" in t)
            assertTrue("duongondro.app/privacy" in t)
            assertTrue("Page ${i + 1} of ${pages.size}" in t)
            // The table's header row repeats after a break.
            assertTrue("Day" in t)
        }
        assertEquals(200, pages.sumOf { p -> texts(p).count { it.startsWith("Day ") } })
    }

    @Test fun nothingBelowTheFooterRule() {
        val rows = (1..120).map { listOf(listOf("x", "1")) }
        val pages = layout.pages(report(ReportSection.Table("T", null, listOf(Column("A", 1f), Column("B", 1f, true)), rows)))
        for (page in pages) {
            val rule = page.draws.filterIsInstance<Draw.Line>().last()
            val body = page.draws.filterIsInstance<Draw.Text>().filter { it.style != ReportStyle.FOOTER }
            assertTrue(body.all { it.y < rule.y1 })
        }
    }

    @Test fun keepsADayTogetherWhereItFits() {
        // Groups of five rows: none may straddle a page break.
        val groups = (1..40).map { d -> (1..5).map { r -> listOf(if (r == 1) "Day $d" else "", "$r") } }
        val pages = layout.pages(report(ReportSection.Table("Sessions", null, listOf(Column("Day", 1f), Column("Count", 1f, true)), groups)))
        assertTrue(pages.size > 1)
        for (page in pages) {
            val counts = page.draws.filterIsInstance<Draw.Text>().filter { it.style == ReportStyle.FIGURE }.map { it.text }
            assertEquals("a page holds whole days", 0, counts.size % 5)
            assertEquals("1", counts.first())
        }
    }

    @Test fun aDayLongerThanAPageNamesItselfAgain() {
        val group = (1..120).map { r -> listOf(if (r == 1) "Monday" else "", "$r") }
        val pages = layout.pages(report(ReportSection.Table("Sessions", null, listOf(Column("Day", 1f), Column("Count", 1f, true)), listOf(group))))
        assertTrue(pages.size > 1)
        for (page in pages) assertTrue("Monday" in texts(page))
    }

    @Test fun secondLineIsMuted() {
        val pages = layout.pages(report(ReportSection.Table("Practices", "2 practices", listOf(Column("Practice", 1f)),
            listOf(listOf(listOf("Dorje Sempa\nDiamond Mind"))))))
        val draws = pages.single().draws.filterIsInstance<Draw.Text>()
        assertEquals(ReportStyle.CELL, draws.first { it.text == "Dorje Sempa" }.style)
        val second = draws.first { it.text == "Diamond Mind" }
        assertEquals(ReportStyle.CELL_SECOND, second.style)
        assertTrue(second.y > draws.first { it.text == "Dorje Sempa" }.y)
        assertTrue("2 practices" in draws.map { it.text })
    }

    @Test fun figuresAlignToTheEnd() {
        val pages = layout.pages(report(ReportSection.Table("T", null, listOf(Column("A", 1f), Column("N", 1f, figures = true)),
            listOf(listOf(listOf("a", "111,111"))))))
        val figure = pages.single().draws.filterIsInstance<Draw.Text>().first { it.text == "111,111" }
        assertTrue(figure.alignEnd)
        assertEquals(ReportStyle.FIGURE, figure.style)
    }

    @Test fun emptyTableSaysSo() {
        val pages = layout.pages(report(ReportSection.Table("Friends", null, listOf(Column("Name", 1f)), emptyList(), empty = "No friends yet.")))
        assertTrue("No friends yet." in texts(pages.single()))
        assertTrue("Name" !in texts(pages.single()))
    }

    @Test fun wrapsAndBreaksLongWords() {
        assertEquals(listOf("aaaa", "bbbb"), layout.wrap("aaaa bbbb", ReportStyle.BODY, 25f))
        assertEquals(listOf("aaaaa", "aaaaa"), layout.wrap("aaaaaaaaaa", ReportStyle.BODY, 25f))
        assertEquals("abc…", layout.ellipsized("abcdefgh", ReportStyle.BODY, 20f))
    }

    @Test fun factsAndParagraphs() {
        val pages = layout.pages(report(
            ReportSection.Facts("1. Account", listOf("Display name" to "Tidy Raccoon")),
            ReportSection.Text("5. What the server keeps", listOf("First.", "Second.")),
        ))
        val t = texts(pages.single())
        assertTrue(listOf("1. Account", "Display name", "Tidy Raccoon", "5. What the server keeps", "First.", "Second.").all { it in t })
    }
}
