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
        assertEquals("BBBB-BBBB", onShow.shown.value)
        assertNull(onShow.codeFor(a))
    }

    /** What Done on the check, an account switch and the purge do: the screens' code goes too. */
    @Test
    fun forgettingClearsWhatTheScreensShow() {
        val a = UUID.randomUUID()
        val onShow = RecoveryCodeOnShow()
        onShow.show("AAAA-AAAA", a)
        onShow.forget()
        assertNull(onShow.shown.value)
        assertNull(onShow.codeFor(a))
        onShow.forget()
        assertNull("forgetting twice is harmless", onShow.shown.value)
    }

    @Test
    fun aCodeWithoutAUserIsShownButNeverReused() {
        val onShow = RecoveryCodeOnShow()
        onShow.show("CCCC-CCCC", null)
        assertEquals("CCCC-CCCC", onShow.shown.value)
        assertNull(onShow.codeFor(null))
    }
}
