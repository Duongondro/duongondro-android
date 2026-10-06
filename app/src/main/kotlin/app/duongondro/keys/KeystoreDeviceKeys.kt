package app.duongondro.keys

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import app.duongondro.core.crypto.DeviceKey
import app.duongondro.core.crypto.DeviceKeyAgreement
import app.duongondro.core.crypto.DeviceKeyStore
import app.duongondro.core.crypto.SoftwareDeviceKey
import app.duongondro.core.crypto.Tier
import app.duongondro.core.crypto.isValidDevicePublicKey
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This device's key on the glowie curve (NIST P-256), in the strongest Android
 * storage that works (design: Keys, Device keys degrade gracefully):
 *
 * - hardware: StrongBox ECDH (API 31+, phones with a StrongBox);
 * - tee: Keystore ECDH in the trusted execution environment (API 31+);
 * - software: a P-256 key sealed by a Keystore AES-256-GCM key (API 28–30, or a
 *   Keystore whose ECDH fails, which some vendors' do).
 *
 * The tier is read back from the Keystore's KeyInfo rather than assumed, and is
 * stored with the key as one record, so a key is never found without its tier.
 * A key is made only when no record exists: a record whose key cannot be read
 * is an error, never a reason to make a new key, since wraps name the old one.
 * Usable after the first unlock, without user presence, so a background sync
 * can unwrap. App data is excluded from backup, so the record never leaves the
 * phone; the Keystore keys could not anyway.
 *
 * NOT YET TESTED ON A DEVICE OR EMULATOR: written against the platform
 * documentation; it compiles, but needs an instrumented test on API 28 and 31+.
 */
class KeystoreDeviceKeys(context: Context, name: String = "duongondro-device-key") : DeviceKeyStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val packageManager = context.applicationContext.packageManager
    private val ecAlias = name
    private val aesAlias = "$name-seal"

    /**
     * The stored key, or null only when neither a record nor a Keystore key
     * exists. A record that does not read, or a Keystore key without a record
     * that cannot be rebuilt, throws: the key is never replaced because a read
     * failed (SharedPreferences reads a corrupt file as empty, for one).
     */
    @Synchronized
    override fun current(): DeviceKey? {
        val tierText = prefs.getString(TIER, null)
        val sealed = prefs.getString(SEALED, null)
        val store = keyStore()
        if (tierText == null) {
            if (sealed != null) throw IllegalStateException("the sealed device key has no tier")
            if (store.containsAlias(ecAlias)) return rebuildRecord(store)
            if (store.containsAlias(aesAlias)) {
                throw IllegalStateException("the device key's sealing key exists but its sealed key is gone")
            }
            return null
        }
        val tier = Tier.of(tierText) ?: throw IllegalStateException("unreadable device key tier: $tierText")
        return if (sealed != null) {
            DeviceKey(SoftwareDeviceKey.fromRaw(unseal(Base64.decode(sealed, Base64.NO_WRAP))), tier)
        } else {
            val entry = store.getEntry(ecAlias, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("the device key record exists but the Keystore has no key")
            DeviceKey(KeystoreAgreement(entry.privateKey, entry.certificate.publicKey as ECPublicKey), tier)
        }
    }

    /** A Keystore key whose record was lost: the record is rebuilt from the key's KeyInfo. */
    private fun rebuildRecord(store: KeyStore): DeviceKey {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw IllegalStateException("a Keystore device key without a record, below API 31")
        }
        val entry = store.getEntry(ecAlias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("the Keystore's device key entry does not read")
        val tier = tierOf(entry.privateKey)
        check(prefs.edit().putString(TIER, tier.raw).remove(SEALED).commit()) { "could not store the device key record" }
        return DeviceKey(KeystoreAgreement(entry.privateKey, entry.certificate.publicKey as ECPublicKey), tier)
    }

    /**
     * The stored key, or a new one: StrongBox where the phone has one, then the
     * TEE, then software. Falling back from storage the phone has is reported
     * as `fellBack`, with what refused; API 28–30 using software is not.
     */
    @Synchronized
    override fun currentOrCreate(): DeviceKeyStore.Created {
        current()?.let { return DeviceKeyStore.Created(it, fellBack = false) }
        val refused = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
                val strongBox = createInKeystore(strongBox = true)
                strongBox.key?.let { return DeviceKeyStore.Created(it, fellBack = false) }
                refused += "StrongBox: ${describe(strongBox.error)}"
            }
            val tee = createInKeystore(strongBox = false)
            tee.key?.let { return DeviceKeyStore.Created(it, fellBack = refused.isNotEmpty(), refused.joinToString("; ").ifEmpty { null }) }
            refused += "Keystore: ${describe(tee.error)}"
        }
        return DeviceKeyStore.Created(createSoftware(), fellBack = refused.isNotEmpty(), refused.joinToString("; ").ifEmpty { null })
    }

    private class Attempt(val key: DeviceKey?, val error: Exception?)

    private fun describe(e: Exception?): String =
        if (e == null) "unknown" else e.javaClass.name + (e.message?.let { ": $it" } ?: "")

    /**
     * A Keystore ECDH key that passes a self-agreement, or why not. Only ever called
     * when [current] found no key under the alias, so on failure the only entry
     * it removes is the one this call just made.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun createInKeystore(strongBox: Boolean): Attempt {
        check(!keyStore().containsAlias(ecAlias)) { "refusing to replace an existing device key" }
        return try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(ecAlias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
            val pair = generator.generateKeyPair()
            val agreement = KeystoreAgreement(pair.private, pair.public as ECPublicKey)
            // Some vendors' Keystores make the key but fail the agreement.
            check(agreement.sharedSecret(agreement.publicKey).size == 32)
            val tier = tierOf(pair.private)
            check(prefs.edit().putString(TIER, tier.raw).remove(SEALED).commit()) { "could not store the device key record" }
            Attempt(DeviceKey(agreement, tier), null)
        } catch (e: Exception) {
            // Only this call's own key can be here: the alias was empty above.
            runCatching { keyStore().deleteEntry(ecAlias) }
            Attempt(null, e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun tierOf(key: PrivateKey): Tier {
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> Tier.HARDWARE
            // Secure hardware the platform cannot name more precisely.
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> Tier.TEE
            // SECURITY_LEVEL_SOFTWARE and SECURITY_LEVEL_UNKNOWN.
            else -> Tier.SOFTWARE
        }
    }

    private fun createSoftware(): DeviceKey {
        check(!keyStore().containsAlias(aesAlias)) { "refusing to replace an existing sealed device key" }
        val key = SoftwareDeviceKey.generate()
        val sealed = seal(key.rawPrivate)
        prefs.edit()
            .putString(TIER, Tier.SOFTWARE.raw)
            .putString(SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .commit().also { check(it) { "could not store the device key" } }
        return DeviceKey(key, Tier.SOFTWARE)
    }

    // The software key, sealed at rest by a Keystore AES-256-GCM key.

    private fun sealingKey(): SecretKey {
        (keyStore().getKey(aesAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(aesAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** iv(12) ‖ ciphertext ‖ tag. */
    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, sealingKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unseal(sealed: ByteArray): ByteArray {
        val key = keyStore().getKey(aesAlias, null) as? SecretKey
            ?: throw IllegalStateException("the sealed device key exists but its sealing key does not")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    /** Forgets the key for good (account deletion, tests): wraps to it can no longer be opened. */
    @Synchronized
    fun delete() {
        prefs.edit().clear().commit()
        runCatching { keyStore().deleteEntry(ecAlias) }
        runCatching { keyStore().deleteEntry(aesAlias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** ECDH with a Keystore private key; peer keys are checked on the curve before use. */
    private class KeystoreAgreement(private val key: PrivateKey, private val public: ECPublicKey) : DeviceKeyAgreement {
        override val publicKey: ByteArray = uncompressed(public)

        override fun sharedSecret(peerPublicKey: ByteArray): ByteArray {
            require(isValidDevicePublicKey(peerPublicKey)) { "not a public key on the glowie curve" }
            val agreement = KeyAgreement.getInstance("ECDH", KEYSTORE)
            agreement.init(key)
            agreement.doPhase(peerKey(peerPublicKey, public.params), true)
            return agreement.generateSecret()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val TIER = "tier"
        const val SEALED = "sealed"

        fun uncompressed(key: ECPublicKey): ByteArray =
            byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

        fun fixed32(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }

        fun peerKey(bytes: ByteArray, params: ECParameterSpec): ECPublicKey {
            val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
            return KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC).generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
        }
    }
}
