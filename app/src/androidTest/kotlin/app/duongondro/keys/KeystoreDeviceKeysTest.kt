package app.duongondro.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.SoftwareDeviceKey
import app.duongondro.core.crypto.Tier
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The Keystore device key on a real phone or emulator: `make device-test`. */
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceKeysTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test-device-key-${System.nanoTime()}"
    private val keys = KeystoreDeviceKeys(context, name)
    private val prefs get() = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)

    @After fun tearDown() = keys.delete()

    @Test fun aKeyIsMadeOnceAndReadBackWithItsTier() {
        val made = keys.currentOrCreate()
        assertFalse("this emulator's Keystore should do ECDH", made.fellBack)
        assertEquals(E2EE.PUBLIC_KEY_SIZE, made.key.publicKey.size)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Keystore ECDH from API 31, in secure hardware unless the Keystore
            // itself is software (an emulator's is): the tier is what it reports.
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey(name, null) as java.security.PrivateKey
            val level = java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, android.security.keystore.KeyInfo::class.java).securityLevel
            val expected = when (level) {
                android.security.keystore.KeyProperties.SECURITY_LEVEL_STRONGBOX -> setOf(Tier.HARDWARE)
                android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE,
                android.security.keystore.KeyProperties.SECURITY_LEVEL_UNKNOWN -> setOf(Tier.SOFTWARE)
                else -> setOf(Tier.TEE)
            }
            assertTrue("security level $level, tier ${made.key.tier}", made.key.tier in expected)
        } else {
            assertEquals(Tier.SOFTWARE, made.key.tier)
        }
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

    /** A lost record never makes a new key: it is rebuilt from the Keystore, or the read fails. */
    @Test fun aLostRecordIsNotAReasonForANewKey() {
        val made = keys.currentOrCreate().key
        prefs.edit().clear().commit()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            assertArrayEquals(made.publicKey, keys.currentOrCreate().key.publicKey)
            assertEquals(made.tier, keys.current()!!.tier)
        } else {
            assertThrows(IllegalStateException::class.java) { keys.currentOrCreate() }
        }
        prefs.edit().putString("tier", "quantum").commit()
        assertThrows(IllegalStateException::class.java) { keys.currentOrCreate() }
    }
}
