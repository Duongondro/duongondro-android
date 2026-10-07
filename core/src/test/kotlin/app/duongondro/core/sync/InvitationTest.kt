package app.duongondro.core.sync

import app.duongondro.core.isV7
import app.duongondro.core.uuidV7
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InvitationTest {
    @Test
    fun lengthTellsAnInviteFromAnAdmissionCode() {
        val secret = ByteArray(10) { it.toByte() }
        val invite = Invitation.parse("7k2mq9xa" + Crockford.encode(secret)) as Invitation.Invite
        assertEquals("7K2MQ9XA", invite.id)
        assertArrayEquals(secret, invite.secret)
        val admission = Invitation.parse("fbpw s3nt yrrn qf4w") as Invitation.Admission
        assertEquals("FBPWS3NTYRRNQF4W", admission.code)
        assertNull(Invitation.parse("FBPWS3NTYRRNQF4"))
        assertNull(Invitation.parse("FBPWS3NTYRRNQF4U")) // U is not Crockford
    }

    @Test
    fun sessionIdsAreVersion7WithTheirMillisecond() {
        val at = Instant.ofEpochMilli(1_759_800_000_123)
        val id = uuidV7(at)
        assertTrue(id.isV7)
        assertEquals(at.toEpochMilli(), id.mostSignificantBits ushr 16)
    }
}
