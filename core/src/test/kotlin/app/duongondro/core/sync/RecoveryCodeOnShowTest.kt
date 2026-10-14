package app.duongondro.core.sync

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryCodeOnShowTest {
    /**
     * Account A's set-up shows a code; Back to the email step, and account B
     * signs up in the same onboarding. B must get no code until its own set-up
     * made one, and A's must never come back for B.
     */
    @Test
    fun aCodeOnShowBelongsToTheAccountItWasMadeFor() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val onShow = RecoveryCodeOnShow()
        assertNull(onShow.codeFor(a))
        onShow.show("AAAA-AAAA", a)
        assertEquals("Back, then forward again: the same code", "AAAA-AAAA", onShow.codeFor(a))
        // B signs in: A's sync state is cleared, so this phone syncs with nobody yet.
        assertNull("B's recovery step sets up B's keys", onShow.codeFor(null))
        assertNull(onShow.codeFor(b))
        onShow.show("BBBB-BBBB", b)
        assertEquals("BBBB-BBBB", onShow.codeFor(b))
        assertNull(onShow.codeFor(a))
        onShow.forget()
        assertNull("confirmed, signed out or switched away", onShow.codeFor(b))
    }
}
