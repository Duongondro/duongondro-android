package app.duongondro.core.sync

import app.duongondro.core.crypto.E2EE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCodeTest {
    /** iOS StatementTests.testRecoveryCodeRoundTripsAndForgivesTyping. */
    @Test fun roundTripsAndForgivesTyping() {
        repeat(50) {
            val secret = E2EE.randomBytes(16)
            val code = RecoveryCode.encode(secret)
            assertEquals(code, 26 + 6, code.length)
            assertArrayEquals(secret, RecoveryCode.decode(code))
            assertArrayEquals(secret, RecoveryCode.decode(code.lowercase().replace("-", " ")))
        }
        assertNull(RecoveryCode.decode("not a code"))
        assertNull("U is not in the alphabet", RecoveryCode.decode("UUUU-UUUU-UUUU-UUUU-UUUU-UUUU-UU"))
    }

    @Test fun fixedCodes() {
        assertEquals("0000-0000-0000-0000-0000-0000-00", RecoveryCode.encode(ByteArray(16)))
        assertEquals("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZW", RecoveryCode.encode(ByteArray(16) { -1 }))
        // The vectors' recovery secret, as both phones show it.
        val secret = byteArrayOf(0x2f, 0xa8.toByte(), 0xac.toByte(), 0x81.toByte(), 0x84.toByte(), 0xc2.toByte(), 0xad.toByte(), 0x51,
            0xae.toByte(), 0xf5.toByte(), 0x35, 0x02, 0x34, 0x43, 0xc2.toByte(), 0xb3.toByte())
        val code = RecoveryCode.encode(secret)
        assertTrue(Regex("([0-9A-HJKMNP-TV-Z]{4}-){6}[0-9A-HJKMNP-TV-Z]{2}").matches(code))
        assertArrayEquals(secret, RecoveryCode.decode(code))
    }

    @Test fun normalisingReadsLookalikes() {
        assertEquals("011ABCD", RecoveryCode.normalise("o-i l\nabcd"))
        val code = RecoveryCode.encode(ByteArray(16))
        assertArrayEquals(ByteArray(16), RecoveryCode.decode(code.replace('0', 'O').lowercase()))
        assertArrayEquals(ByteArray(16) { -1 }, RecoveryCode.decode("zzzz zzzz zzzz zzzz zzzz zzzz zw"))
    }

    @Test fun wrongLengthsAreRefused() {
        val code = RecoveryCode.normalise(RecoveryCode.encode(E2EE.randomBytes(16)))
        assertNull(RecoveryCode.decode(code.dropLast(1)))
        assertNull(RecoveryCode.decode(code + "0"))
        assertNull(RecoveryCode.decode(""))
    }

    /** iOS InviteLinkTests: Crockford round trips for link ids (5 bytes) and secrets (10). */
    @Test fun crockfordRoundTrips() {
        for (n in listOf(5, 10)) {
            val data = E2EE.randomBytes(n)
            val text = Crockford.encode(data)
            assertEquals(n * 8 / 5, text.length)
            assertArrayEquals(data, Crockford.decode(text))
            assertArrayEquals(data, Crockford.decode(text.lowercase()))
        }
        assertNull(Crockford.decode("ABCDEFG"))
        assertNull(Crockford.decode("ABCDEFGU"))
    }
}
