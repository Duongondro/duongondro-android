package app.duongondro.data

import android.app.NotificationManager
import android.content.Context
import app.duongondro.model.AppModel
import app.duongondro.reminders.Reminders
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.KeyStore

/**
 * The local half of "Delete everything" (design: Data export and deletion ›
 * Purge). With an account, `DELETE /api/me` comes first (AppModel.purge) and
 * this runs only after the server confirms; in local mode there is nothing on
 * any server. The account's preferences go in AccountManager.forget.
 * Every step runs even if an earlier one fails; the first error is thrown at
 * the end, so a database error never leaves keys or files behind.
 */
object Purge {
    suspend fun run(context: Context, model: AppModel) {
        model.discardInFlight()
        var failure: Throwable? = null
        try { model.store.eraseAll() } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e }
        // The device key's record and the sealed secrets go with their Keystore keys.
        try { app.duongondro.keys.KeystoreDeviceKeys(context).delete() } catch (e: Exception) { failure = failure ?: e }
        try { deleteKeystoreEntries() } catch (e: Exception) { failure = failure ?: e }
        app.duongondro.keys.SecretFiles.folder(context).deleteRecursively()
        listOf(Exports.folder(context), Covers.folder(context)).forEach { it.deleteRecursively() }
        Reminders.reschedule(context, model.snapshot.value)
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
        failure?.let { throw it }
    }

    /** Every Android Keystore entry the app owns, the device key with them. */
    private fun deleteKeystoreEntries() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.aliases().toList().forEach { ks.deleteEntry(it) }
    }
}

/** Local-only cover photos: never uploaded, never backed up. */
object Covers {
    fun folder(context: Context) = File(context.filesDir, "covers")

    fun all(context: Context): Map<String, ByteArray> =
        folder(context).listFiles { f -> f.name.endsWith(".jpg") }?.associate { it.name.removeSuffix(".jpg") to it.readBytes() } ?: emptyMap()
}

/** Where the export ZIP waits for the share sheet; emptied on every export and by the purge. */
object Exports {
    fun folder(context: Context) = File(context.cacheDir, "export")

    fun write(context: Context, bytes: ByteArray, name: String): File {
        val dir = folder(context)
        dir.deleteRecursively()
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(bytes) }
    }
}
