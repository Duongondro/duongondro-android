package app.duongondro.core.qr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * iOS's QRCodeTests, less the CoreImage round trips: those run on the Mac when
 * qr-cases.json is made (Scripts/qr-cases), and here every module must match
 * what iOS drew for the same text, version and mask.
 */
class QRCodeTest {
    private val invite = "HTTPS://DUONGONDRO.APP/I/7K2MQ9XA#H4N8R2CJ6TPW3ZQF"
    private val friend = "HTTPS://DUONGONDRO.APP/F/7K2MQ9XA#H4N8R2CJ6TPW3ZQF"

    @Test
    fun inviteLinksFitVersion3AtM() {
        for (url in listOf(invite, friend)) {
            val qr = QRCode.encode(url, ECC.MEDIUM)
            assertEquals(url, 3, qr.version)
            assertEquals(url, 29, qr.size)
        }
    }

    @Test
    fun splitKeepsHashAsOneByteSegment() {
        assertEquals(listOf(Segment.Mode.ALPHANUMERIC, Segment.Mode.BYTE, Segment.Mode.ALPHANUMERIC), Segment.split(invite).map { it.mode })
    }

    @Test
    fun splitMergesTinySegments() {
        // A lone digit between letters is cheaper inside the alphanumeric run.
        assertEquals(listOf(Segment.Mode.ALPHANUMERIC), Segment.split("AB1CD").map { it.mode })
        assertEquals(listOf(Segment.Mode.NUMERIC), Segment.split("1234567890").map { it.mode })
    }

    @Test
    fun finderModules() {
        val qr = QRCode.encode("HELLO")
        assertTrue(qr.isFinderModule(0, 0))
        assertTrue(qr.isFinderModule(qr.size - 1, 6))
        assertTrue(qr.isFinderModule(6, qr.size - 1))
        assertFalse(qr.isFinderModule(7, 7))
        assertFalse(qr.isFinderModule(qr.size - 1, qr.size - 1))
    }

    @Test
    fun tooLongThrows() {
        try {
            QRCode.encode("a".repeat(400))
            fail("400 bytes fitted")
        } catch (e: QRException) {
            assertEquals(QRException.Reason.TOO_LONG, e.reason)
        }
    }

    @Test
    fun minimumVersion() {
        assertEquals(5, QRCode.encode("HELLO", minVersion = 5).version)
    }

    @Test
    fun specExampleCodewords() {
        // ISO/IEC 18004 Annex I: "01234567" at 1-M is version 1, 16 data + 10 ECC codewords.
        assertEquals(1, QRCode.encode("01234567", ECC.MEDIUM).version)
        val data = QRCode.testPadded(listOf(Segment.numeric("01234567")), 1, ECC.MEDIUM)
        assertArrayEquals(intArrayOf(0x10, 0x20, 0x0C, 0x56, 0x61, 0x80, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11), data)
        val all = QRCode.testInterleaved(data, 1, ECC.MEDIUM)
        assertArrayEquals(intArrayOf(0xA5, 0x24, 0xD4, 0xC1, 0xED, 0x36, 0xC7, 0x87, 0x2C, 0x55), all.copyOfRange(all.size - 10, all.size))
    }

    @Serializable
    private class Case(val text: String, val ecc: String, val minVersion: Int, val version: Int, val mask: Int, val rows: List<String>)

    @Test
    fun drawsWhatIosDraws() {
        val json = javaClass.getResource("/qr-cases.json")!!.readText()
        val cases = Json.decodeFromString<List<Case>>(json)
        assertTrue(cases.size >= 50)
        assertTrue("a version 7+ case carries the version word", cases.any { it.version >= 7 })
        for (c in cases) {
            val qr = QRCode.encode(c.text, ECC.valueOf(c.ecc), c.minVersion)
            val label = "${c.text} at ${c.ecc}"
            assertEquals(label, c.version, qr.version)
            assertEquals(label, c.mask, qr.mask)
            val rows = (0 until qr.size).map { y -> (0 until qr.size).joinToString("") { x -> if (qr[x, y]) "1" else "0" } }
            assertEquals(label, c.rows, rows)
        }
    }
}
