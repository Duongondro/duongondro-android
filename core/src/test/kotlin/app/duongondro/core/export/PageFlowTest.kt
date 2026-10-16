package app.duongondro.core.export

import app.duongondro.core.export.PageFlow.Block as B
import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS's PageFlowTests, case for case. */
class PageFlowTest {
    private fun blocks(pages: List<List<PageFlow.Placement>>) = pages.map { p -> p.filterIsInstance<PageFlow.Placement.At>().map { it.index } }

    @Test fun flowsOntoNewPages() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), blocks(PageFlow.paginate(List(5) { B(40f) }, 100f)))
    }

    @Test fun headingNeverEndsAPage() {
        val p = PageFlow.paginate(listOf(B(70f), B(20f, keepWithNext = true), B(20f)), 100f)
        assertEquals(listOf(listOf(0), listOf(1, 2)), blocks(p))
    }

    @Test fun tableHeaderRepeatsAfterABreak() {
        val p = PageFlow.paginate(listOf(B(10f, keepWithNext = true)) + List(4) { B(30f, table = 7) }, 100f, mapOf(7 to 10f))
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4)), blocks(p))
        assertEquals(PageFlow.Placement.Header(7, 0f), p[1].first())
        assertEquals(PageFlow.Placement.At(4, 10f), p[1].last())
    }

    @Test fun dayKeptTogetherWhereItFits() {
        val rows = listOf(B(30f, table = 1, group = 1), B(30f, table = 1, group = 2), B(30f, table = 1, group = 2))
        val p = PageFlow.paginate(listOf(B(20f)) + rows, 100f, mapOf(1 to 10f))
        assertEquals("the second day moves whole", listOf(listOf(0, 1), listOf(2, 3)), blocks(p))
        assertEquals(PageFlow.Placement.Header(1, 0f), p[1].first())
    }

    @Test fun aDayTallerThanAPageBreaksInside() {
        val p = PageFlow.paginate(listOf(B(50f)) + List(5) { B(30f, table = 1, group = 1) }, 100f, mapOf(1 to 10f))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3, 4), listOf(5)), blocks(p))
    }

    @Test fun gapsCollapseAtBreaksAndTheEnd() {
        // A gap that would overflow is dropped, not carried to the next page's top.
        val p = PageFlow.paginate(listOf(B(90f), B(18f, gap = true), B(40f), B(18f, gap = true)), 100f)
        assertEquals(listOf(listOf(0), listOf(2, 3)), blocks(p))
        // A trailing gap makes no blank page.
        assertEquals(1, PageFlow.paginate(listOf(B(95f), B(18f, gap = true)), 100f).size)
    }
}
