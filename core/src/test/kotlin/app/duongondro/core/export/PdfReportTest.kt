package app.duongondro.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.format.DateTimeFormatter

class PdfReportTest {
    private val text = PdfText(
        title = "Duongöndro: your data", meta = "Exported", footer = "duongondro.app/privacy", page = { p, n -> "Page $p of $n" },
        number = { "%,d".format(it) }, date = { at, _ -> at?.toString()?.take(10) ?: "–" }, day = { d, _ -> d.toString() },
        time = { at, zone -> at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) },
        word = { it }, plural = { key, n -> key.replace("%lld", "$n") },
        genderName = { it ?: "Not given" }, tierName = { it }, added = { c, u -> "Added $c" + (u?.let { ", last used $it" } ?: "") },
        continued = { "$it (continued)" },
    )

    private fun headings(r: Report) = r.blocks.filterIsInstance<ReportBlock.Heading>().map { it.title }
    private fun tables(r: Report) = r.blocks.filterIsInstance<ReportBlock.Table>()

    @Test fun sectionsAsIos() {
        assertEquals(
            listOf("1. Account", "2. Practices", "3. Sessions · Dorje Sempa", "3. Sessions · Chenrezig", "4. Friends", "5. What the server keeps"),
            headings(PdfReport.build(ExportFixtures.content(), text)),
        )
    }

    @Test fun practicesRows() {
        val rows = tables(PdfReport.build(ExportFixtures.content(), text))[0].rows.map { it.cells }
        assertEquals(listOf(Cell("Dorje Sempa", "Diamond Mind"), Cell("111,219"), Cell("111,111"), Cell("2", "108"), Cell("0 days"),
            Cell("1 days"), Cell("Yes")), rows[0])
        assertEquals(Cell("Streak only"), rows[1][1])
        assertEquals("Archived", rows[2][0].second)
    }

    @Test fun sessionsGroupedByDayWithTheCountedForDay() {
        val r = PdfReport.build(ExportFixtures.content(), text)
        val dorje = tables(r)[1].rows
        assertEquals(listOf("2026-10-13", "06:40", "1,000", "1", ""), dorje[0].cells.map { it.main })
        assertEquals(listOf("", "07:05", "111", "1", ""), dorje[1].cells.map { it.main })
        assertEquals(listOf("2026-10-14", "00:20", "108", "2", "2026-10-13"), dorje[2].cells.map { it.main })
        assertEquals(dorje[0].group, dorje[1].group)
        assertTrue(dorje[1].group != dorje[2].group)
        assertEquals("Done", tables(r)[2].rows[0].cells[2].main)
        assertEquals("3 sessions", r.blocks.filterIsInstance<ReportBlock.Heading>()[2].detail)
    }

    @Test fun localModeSaysSo() {
        val r = PdfReport.build(ExportFixtures.content(withServer = false).copy(account = null), text)
        val paragraphs = r.blocks.filterIsInstance<ReportBlock.Paragraph>().map { it.text }
        assertTrue("No online account: everything below is on this phone only." in paragraphs)
        assertTrue("No online account" in paragraphs)
        assertTrue("Nothing: this phone has no online account." in paragraphs)
    }

    @Test fun unreachableServerSaysSo() {
        val r = PdfReport.build(ExportFixtures.content(withServer = false), text)
        assertTrue(r.blocks.any { it is ReportBlock.Paragraph && it.text == PdfReport.SERVER_UNREACHABLE })
        assertTrue(r.blocks.none { it is ReportBlock.Pair && it.label == "Passkeys" })
    }

    @Test fun accountListsPasskeysAndDevices() {
        val pairs = PdfReport.build(ExportFixtures.content(), text).blocks.filterIsInstance<ReportBlock.Pair>().associate { it.label to it.value }
        assertEquals("1 passkeys\nAdded 2026-10-02", pairs["Passkeys"])
        assertEquals("1 devices\nhardware · Added 2026-10-01", pairs["Devices"])
        assertEquals("Tidy Raccoon", pairs["Display name"])
        assertEquals("–", pairs["Username"])
    }

    @Test fun theServerListsInFull() {
        val bullets = PdfReport.build(ExportFixtures.content(), text).blocks.filterIsInstance<ReportBlock.Paragraph>().filter { it.bullet }
        assertEquals(PdfReport.PRIVACY.sumOf { it.items.size }, bullets.size)
        val paragraphs = PdfReport.build(ExportFixtures.content(), text).blocks.filterIsInstance<ReportBlock.Paragraph>().map { it.text }
        assertTrue("The last item is a real limit. These times and sizes can show when you practise, even for a private streak." in paragraphs)
        assertEquals("Dorje Sempa (continued)", (PdfReport.build(ExportFixtures.content(), text).blocks.filterIsInstance<ReportBlock.Table>()[1]).continued)
    }
}
