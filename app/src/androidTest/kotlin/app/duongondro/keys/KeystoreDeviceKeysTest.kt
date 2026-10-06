package app.duongondro.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.SoftwareDeviceKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The Keystore device key on a real phone or emulator: `make device-test`. */
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceKeysTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = KeystoreDeviceKeys(context, "test-device-key-${System.nanoTime()}")

    @After fun tearDown() = keys.delete()

    @Test fun aKeyIsMadeOnceAndReadBackWithItsTier() {
        val made = keys.currentOrCreate()
        assertFalse("this emulator's Keystore should do ECDH", made.fellBack)
        assertEquals(E2EE.PUBLIC_KEY_SIZE, made.key.publicKey.size)
        val again = keys.currentOrCreate().key
        assertArrayEquals(made.key.publicKey, again.publicKey)
        assertEquals(made.key.tier, again.tier)
        assertArrayEquals(made.key.publicKey, keys.current()!!.publicKey)
    }

    @Test fun wrapsToTheKeyOpen() {
        val key = keys.currentOrCreate().key
        val secret = E2EE.randomBytes(32)
        val aad = E2EE.wrapAAD(UUID.randomUUID(), 1, UUID.randomUUID(), E2EE.WrapKind.PRACTICE_KEY)
        val wrapped = E2EE.wrap(secret, key.publicKey, aad)
        assertArrayEquals(secret, E2EE.unwrap(wrapped, keys.current()!!.agreement, aad))
        // Agreement matches the software implementation from the other side.
        val peer = SoftwareDeviceKey.generate()
        assertArrayEquals(peer.sharedSecret(key.publicKey), key.agreement.sharedSecret(peer.publicKey))
    }
}
