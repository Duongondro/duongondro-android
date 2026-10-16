package app.duongondro.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** The same tables and columns as iOS's CSVExport (ExportFormatsTests there). */
class CsvExportTest {
    private val content = ExportFixtures.content()
    private val header: (CsvExport.Column) -> String = { it.name.lowercase() }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun lines(name: String) =
        CsvExport.table(CsvExport.tables(content).first { it.name == name }, header).removePrefix(Csv.BOM).split("\r\n").dropLast(1)

    @Test fun tablesByWhatTheExportHas() {
        val files = unzip(CsvExport.zip(content, header))
        assertEquals(listOf("README.txt", "practices.csv", "sessions.csv", "friends.csv", "invites.csv", "blocks.csv", "reports.csv", "devices.csv"),
            files.keys.toList())
        assertTrue(files.filterKeys { it.endsWith(".csv") }.values.all { it.startsWith(Csv.BOM) && it.endsWith("\r\n") })
        assertTrue("Values that are codes stay English" in files.getValue("README.txt"))
        // An account without the server's part: friends as cached, nothing only the server holds.
        assertEquals(listOf("practices", "sessions", "friends"), CsvExport.tables(ExportFixtures.content(withServer = false)).map { it.name })
        // Local mode: no account, no friends.
        assertEquals(listOf("practices", "sessions"),
            CsvExport.tables(ExportFixtures.content(withServer = false).copy(account = null)).map { it.name })
    }

    @Test fun practicesUnformattedAndGuarded() {
        assertEquals(listOf(
            "practice,second_name,target,mala_size,streak_only,archived",
            "Dorje Sempa,Diamond Mind,111111,108,false,false",
            "Chenrezig,Loving Eyes,1000000,108,true,false",
            "'=cmd|' /C calc'!A0,,,108,false,true",
        ), lines("practices"))
    }

    @Test fun sessionsLocalToWhereTheyStarted() {
        val lines = lines("sessions")
        assertEquals("practice,day_counted,start,time_zone,count,round,chosen_day", lines[0])
        assertEquals("Dorje Sempa,2026-10-13,2026-10-13 06:40:00,Europe/Warsaw,1000,1,", lines[1])
        assertEquals("Dorje Sempa,2026-10-13,2026-10-14 00:20:00,Europe/Warsaw,108,2,2026-10-13", lines[3])
        assertEquals("Chenrezig,2026-10-14,2026-10-14 19:30:00,Europe/Warsaw,0,,", lines[4])
    }

    @Test fun serverTablesInThePhonesZone() {
        assertEquals(listOf("display_name,friends_since", "Quiet Heron,2026-10-05T10:00:00+02:00"), lines("friends"))
        assertEquals(listOf("code,kind,created,expires,revoked,redemptions", "inv1,made,2026-10-04T10:00:00+02:00,2026-10-11T10:00:00+02:00,,1"),
            lines("invites"))
        assertEquals(listOf("person,created", "00000000-0000-0000-0000-000000000003,2026-10-06T10:00:00+02:00"), lines("blocks"))
        assertEquals(listOf("person,reason,created", "00000000-0000-0000-0000-000000000003,\"'-spam, and \"\"worse\"\"\",2026-10-06T10:01:00+02:00"),
            lines("reports"))
        assertEquals(listOf("device,key_storage,created", "00000000-0000-0000-0000-00000000000d,hardware,2026-10-01T12:00:00+02:00"), lines("devices"))
    }

    @Test fun aFriendWithoutANameAndUnreadableSessions() {
        val nameless = content.copy(friends = listOf(content.friends.single().copy(displayName = "")), unreadable = 2)
        assertEquals("Ein Freund", CsvExport.tables(nameless, aFriend = "Ein Freund").first { it.name == "friends" }.rows.single()[0])
        assertTrue("2 sealed sessions could not be opened" in CsvExport.readme(nameless))
        assertTrue("could not be reached" in CsvExport.readme(ExportFixtures.content(withServer = false)))
        assertTrue("no online account" in CsvExport.readme(ExportFixtures.content(withServer = false).copy(account = null)))
        assertTrue("could not be reached" !in CsvExport.readme(content))
    }
}
