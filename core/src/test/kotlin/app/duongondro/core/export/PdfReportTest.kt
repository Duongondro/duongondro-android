package app.duongondro.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.format.DateTimeFormatter

class PdfReportTest {
    private val text = PdfText(
        title = "Duongöndro: your data", meta = "Exported", footer = "duongondro.app/privacy", page = { p, n -> "Page $p of $n" },
        number = { "%,d".format(it) }, date = { it.toString().take(10) }, day = { it.toString() },
        time = { at, zone -> at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) },
        none = "—", yes = "yes", no = "no",
        accountTitle = "Account", displayName = "Display name", username = "Username", gender = "Gender", email = "E-mail",
        accountCreated = "Created", passkeys = "Passkeys", devices = "Devices", notGiven = "not given", localMode = "Local mode",
        serverMissing = "Not fetched", genderName = { it }, passkeyValue = { c, u -> "added $c" + (u?.let { ", used $it" } ?: "") },
        deviceValue = { tier, added -> "$tier, added $added" },
        practicesTitle = "Practices", practicesCount = { "$it practices" }, practiceColumn = "Practice", totalColumn = "Total",
        targetColumn = "Target", roundColumn = "Round", streakColumn = "Streak", publicColumn = "Public", archived = "archived",
        streakValue = { c, l -> "$c days · longest $l" },
        sessionsTitle = "Sessions", sessionsCount = { "$it sessions" }, dayColumn = "Day", startedColumn = "Started",
        countColumn = "Count", countedForColumn = "Counted for", done = "done", noSessions = "No sessions.",
        friendsTitle = "Friends", friendsCount = { "$it friends" }, friendColumn = "Friend", sinceColumn = "Since", noFriends = "No friends.",
        serverTitle = "What the server keeps", serverParagraphs = listOf("Sealed.", "Encrypted.", "Plain."),
    )

    @Test fun sectionsInTheDocsOrder() {
        val report = PdfReport.build(ExportFixtures.content(), text)
        assertEquals(
            listOf("1. Account", "2. Practices", "3. Sessions · Dorje Sempa", "3. Sessions · Chenrezig", "4. Friends", "5. What the server keeps"),
            report.sections.map { it.title },
        )
    }

    @Test fun practicesRows() {
        val table = PdfReport.build(ExportFixtures.content(), text).sections[1] as ReportSection.Table
        val rows = table.groups.map { it.single() }
        assertEquals(listOf("Dorje Sempa\nDiamond Mind", "111,219", "111,111", "2", "0 days · longest 1", "yes"), rows[0])
        assertEquals("—", rows[1][1])
        assertEquals("'=cmd|' /C calc'!A0".drop(1) + "\narchived", rows[2][0])
    }

    @Test fun sessionsGroupedByDayWithTheCountedForDay() {
        val table = PdfReport.build(ExportFixtures.content(), text).sections[2] as ReportSection.Table
        assertEquals("3 sessions", table.detail)
        assertEquals(2, table.groups.size)
        assertEquals(listOf("2026-10-13", "06:40", "1,000", "1", ""), table.groups[0][0])
        assertEquals(listOf("", "07:05", "111", "1", ""), table.groups[0][1])
        assertEquals(listOf("2026-10-14", "00:20", "108", "2", "2026-10-13"), table.groups[1][0])
        val chenrezig = PdfReport.build(ExportFixtures.content(), text).sections[3] as ReportSection.Table
        assertEquals("done", chenrezig.groups[0][0][2])
    }

    @Test fun localModeSaysSo() {
        val content = ExportFixtures.content(withServer = false).copy(account = null)
        val report = PdfReport.build(content, text)
        val account = report.sections[0] as ReportSection.Facts
        assertEquals(listOf("Account" to "Local mode"), account.pairs)
        val friends = report.sections.first { it.title == "4. Friends" } as ReportSection.Table
        assertEquals("Local mode", friends.empty)
        assertTrue(friends.groups.isEmpty())
    }

    @Test fun accountListsPasskeysAndDevices() {
        val account = PdfReport.build(ExportFixtures.content(), text).sections[0] as ReportSection.Facts
        assertTrue("Passkeys" to "added 2026-10-02" in account.pairs)
        assertTrue("Devices" to "hardware, added 2026-10-01" in account.pairs)
        assertTrue("Display name" to "Tidy Raccoon" in account.pairs)
    }
}
