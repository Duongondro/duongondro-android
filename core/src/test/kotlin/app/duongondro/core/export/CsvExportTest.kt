package app.duongondro.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

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

    @Test fun everyTableEvenEmpty() {
        val files = unzip(CsvExport.zip(content, header))
        assertEquals(listOf("practices.csv", "sessions.csv", "friends.csv", "invites.csv", "blocks.csv", "reports.csv", "devices.csv"),
            files.keys.toList())
        assertTrue(files.values.all { it.endsWith("\r\n") })
        val local = unzip(CsvExport.zip(ExportFixtures.content(withServer = false), header))
        assertEquals("account_id,blocked_since\r\n", local.getValue("blocks.csv"))
    }

    @Test fun practicesUnformattedAndGuarded() {
        val csv = CsvExport.table(CsvExport.Table.PRACTICES, content, header)
        assertEquals(
            "practice_name,practice_second_name,target,mala_size,streak_only,archived,total\r\n" +
                "Dorje Sempa,Diamond Mind,111111,,false,false,111219\r\n" +
                "Chenrezig,Loving Eyes,1000000,,true,false,0\r\n" +
                "'=cmd|' /C calc'!A0,,,,false,true,0\r\n",
            csv,
        )
    }

    @Test fun sessionsWithLocalStartAndChoice() {
        val lines = CsvExport.table(CsvExport.Table.SESSIONS, content, header).split("\r\n")
        assertEquals("session_practice,day_counted,start,count,round,after_midnight_choice", lines[0])
        assertEquals("Dorje Sempa,2026-10-13,2026-10-13T06:40:00+02:00,1000,1,", lines[1])
        assertEquals("Dorje Sempa,2026-10-13,2026-10-14T00:20:00+02:00,108,2,2026-10-13", lines[3])
        assertEquals("Chenrezig,2026-10-14,2026-10-14T19:30:00+02:00,0,,", lines[4])
    }

    @Test fun serverTables() {
        assertEquals("friend_name,friend_since\r\nQuiet Heron,2026-10-05T08:00:00Z\r\n", CsvExport.table(CsvExport.Table.FRIENDS, content, header))
        assertEquals("invite_id,created,expires,revoked,redeemed\r\ninv1,2026-10-04T08:00:00Z,2026-10-11T08:00:00Z,,1\r\n",
            CsvExport.table(CsvExport.Table.INVITES, content, header))
        assertEquals("account_id,reason,reported_at\r\n00000000-0000-0000-0000-000000000003,\"'-spam, and \"\"worse\"\"\",2026-10-06T08:01:00Z\r\n",
            CsvExport.table(CsvExport.Table.REPORTS, content, header))
        assertEquals("device_id,key_storage,added\r\n00000000-0000-0000-0000-00000000000d,hardware,2026-10-01T10:00:00Z\r\n",
            CsvExport.table(CsvExport.Table.DEVICES, content, header))
    }
}
