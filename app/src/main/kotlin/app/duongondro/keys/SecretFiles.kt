package app.duongondro.keys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.duongondro.core.sync.SecretStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The account's secrets (identity seed, practice keys, the pending recovery
 * secret, the session token), each a file in app-private storage sealed with
 * AES-256-GCM under one Android Keystore key, which never leaves the Keystore.
 * Like the device key: usable after the first unlock without user presence, so
 * a background sync can read them; excluded from backups and device transfer
 * (data_extraction_rules.xml), so they never leave the phone.
 *
 * A file that exists but does not open throws: an unreadable secret is not a
 * missing one, and a new key must never be made over an old one.
 */
class SecretFiles(context: Context, private val alias: String = "duongondro-secrets") : SecretStore {
    private val dir = folder(context)

    @Synchronized
    override fun read(name: String): ByteArray? {
        val file = file(name)
        if (!file.exists()) return null
        val sealed = file.readBytes()
        check(sealed.size > IV_SIZE) { "the secret $name is truncated" }
        val key = existingKey() ?: throw IllegalStateException("the secret $name exists but its Keystore key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
        cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    @Synchronized
    override fun write(name: String, data: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(name.toByteArray())
        val sealed = cipher.iv + cipher.doFinal(data)
        check(cipher.iv.size == IV_SIZE) { "unexpected IV size" }
        dir.mkdirs()
        // Written beside, then renamed over: a crash never leaves half a secret.
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(sealed)
        check(tmp.renameTo(file(name))) { "could not store the secret $name" }
    }

    @Synchronized
    override fun delete(name: String) {
        val f = file(name)
        check(!f.exists() || f.delete()) { "could not delete the secret $name" }
    }

    private fun file(name: String): File {
        require(name.matches(Regex("[a-z0-9-]+"))) { "a secret name is lowercase letters, digits and hyphens" }
        return File(dir, "$name.bin")
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        fun folder(context: Context) = File(context.applicationContext.noBackupFilesDir, "secrets")
    }
}
