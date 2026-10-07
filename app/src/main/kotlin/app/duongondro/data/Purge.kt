package app.duongondro.data

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.annotation.DrawableRes
import app.duongondro.R
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

/**
 * Local-only cover photos: never uploaded, never backed up (the extraction
 * rules exclude the files domain). A practice's cover is the person's own
 * photo if they chose one, else the thangka of a built-in ngöndro practice.
 */
object Covers {
    fun folder(context: Context) = File(context.filesDir, "covers")

    private fun file(context: Context, practiceId: String) = File(folder(context), "$practiceId.jpg")

    /** The person's own photo for a practice, if they chose one. */
    fun photo(context: Context, practiceId: String): Bitmap? =
        file(context, practiceId).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** The thangka a built-in practice shows by default, if the app has one. */
    @DrawableRes
    fun builtIn(practiceId: String): Int? = when (practiceId) {
        "refuge" -> R.drawable.cover_refuge
        "dorje-sempa" -> R.drawable.cover_dorje_sempa
        "mandala" -> R.drawable.cover_mandala
        "guru-yoga" -> R.drawable.cover_guru_yoga
        "8th-karmapa" -> R.drawable.cover_8th_karmapa
        else -> null
    }

    fun has(context: Context, practiceId: String) = file(context, practiceId).isFile || builtIn(practiceId) != null

    /**
     * Saves a chosen photo, scaled down and re-encoded (which also drops its
     * location and camera metadata) in the app's private storage. The decoder
     * applies the photo's rotation. Returns false when it cannot be read.
     */
    fun save(context: Context, uri: Uri, practiceId: String): Boolean = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, LONGEST_SIDE / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        folder(context).mkdirs()
        val target = file(context, practiceId)
        val temp = File(folder(context), "$practiceId.tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        check(temp.renameTo(target)) { "could not store the cover" }
        true
    } catch (e: Exception) {
        false
    }

    fun remove(context: Context, practiceId: String) {
        file(context, practiceId).delete()
    }

    fun all(context: Context): Map<String, ByteArray> =
        folder(context).listFiles { f -> f.name.endsWith(".jpg") }?.associate { it.name.removeSuffix(".jpg") to it.readBytes() } ?: emptyMap()

    private const val LONGEST_SIDE = 1600f
    private const val QUALITY = 85
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
