package app.duongondro.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.FileProvider
import app.duongondro.BuildConfig
import app.duongondro.R
import app.duongondro.account.AccountStatus
import app.duongondro.core.export.ExportContent
import app.duongondro.data.DataExport
import app.duongondro.data.ExportFiles
import app.duongondro.data.ExportFormat
import app.duongondro.data.Exports
import app.duongondro.inAppLanguage
import app.duongondro.model.AppModel
import app.duongondro.ui.RowDivider
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Settings › Your data › Export, as CodeShare's Manage my data does it (and iOS's
 * ExportController): one row per format, the open undo window closed first, the file
 * built on this phone (offline and in local mode from the local database alone) while
 * the screen shows progress (the PDF drawn off the main thread), then the share sheet;
 * the file is removed once the share sheet closes, or at once when sharing fails. From
 * the delete confirmation, the dialog is dismissed before the export starts, so the share
 * sheet never opens under it.
 */
@Stable
class ExportController(private val context: Context, private val model: AppModel, private val scope: CoroutineScope) {
    var working by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
    /** The last export went out without the server's part. */
    var serverMissing by mutableStateOf(false)
        private set
    internal var share: (File, ExportFormat) -> Unit = { _, _ -> }

    fun export(format: ExportFormat) {
        if (working) return
        working = true
        scope.launch {
            try {
                val now = Instant.now()
                val file = build(format, now)
                try {
                    share(file, format)
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing half-made stays behind.
                Exports.folder(context).deleteRecursively()
                problem = e.message ?: e.toString()
            } finally {
                working = false
            }
        }
    }

    /**
     * The chosen format's file, from the local database and, with a ready account, the
     * server's export; offline or failed, from the phone alone, and the screen says so.
     */
    private suspend fun build(format: ExportFormat, now: Instant): File {
        // The words and practice names in the app's language, which the application context lacks before API 33.
        val localized = context.inAppLanguage()
        // Final sessions only: the open undo window closes first.
        model.finalisePendingNow()
        val accounts = model.accounts
        var missing = false
        val server = try {
            accounts?.serverExport()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            missing = true
            null
        }
        // An account that is signed out or still without keys cannot be asked either.
        val accountWithoutServer = server == null && accounts?.hasAccount() == true
        serverMissing = missing || accountWithoutServer
        val known = accounts?.knownSessionIds().orEmpty()
        val snapshot = model.snapshot.value
        return withContext(Dispatchers.IO) {
            val bytes = when (format) {
                ExportFormat.JSON -> DataExport.zip(snapshot, server, known, accountWithoutServer,
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", now)
                ExportFormat.PDF, ExportFormat.CSV -> {
                    val state = accounts?.state?.value
                    val account = state?.takeIf { it.status != AccountStatus.NONE }?.let {
                        ExportContent.AccountFacts(it.displayName, it.username, it.gender?.wire, listOfNotNull(it.email), null)
                    }
                    val friends = if (account == null) emptyList()
                        else accounts.friends.value.friends.map { ExportContent.FriendRow(it.user, it.displayName, it.gender, null) }
                    val content = ExportFiles.content(localized, snapshot, server, known, account, friends, now)
                    if (format == ExportFormat.PDF) ExportFiles.pdf(localized, content, buildLabel(), now) else ExportFiles.csv(localized, content, now)
                }
            }
            Exports.write(context, bytes, format.fileName(now))
        }
    }
}

/** The controller, with the share sheet it opens; the file goes when the sheet closes. */
@Composable
fun rememberExportController(model: AppModel): ExportController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(model) { ExportController(context.applicationContext, model, scope) }
    var shared by remember { mutableStateOf<File?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        shared?.delete()
        shared = null
    }
    controller.share = { file, format ->
        shared = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
        val send = Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser.launch(Intent.createChooser(send, null))
    }
    return controller
}

/** "0.4 (12) · 3f9c2e1", with -dirty on a development build, as iOS stamps its PDF. */
private fun buildLabel(): String {
    val revision = BuildConfig.GIT_REVISION
    val short = (if (revision == "unknown") revision else revision.take(7)) + if (BuildConfig.GIT_DIRTY) "-dirty" else ""
    return "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · $short"
}

/** The three rows, in CodeShare's order: PDF, JSON, CSV. */
@Composable
fun ExportRows(controller: ExportController) {
    ExportFormat.entries.forEachIndexed { i, format ->
        if (i > 0) RowDivider()
        ExportRow(stringResource(format.rowTitle), enabled = !controller.working) { controller.export(format) }
    }
}

val ExportFormat.rowTitle: Int
    get() = when (this) {
        ExportFormat.PDF -> R.string.ex_export_all_my_data_as_pdf
        ExportFormat.JSON -> R.string.ex_export_all_my_data_as_json
        ExportFormat.CSV -> R.string.ex_export_all_my_data_as_csv
    }

/** An accent text action filling its row, as CodeShare's Manage my data. */
@Composable
private fun ExportRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, style = Theme.type.body, color = if (enabled) Theme.colors.accent else Theme.colors.muted,
        modifier = Modifier.fillMaxWidth().heightIn(min = Size.minTap).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
    )
}

/** "Export my data first" on the delete confirmation: the three formats, or Cancel. */
@Composable
fun ExportFirstDialog(controller: ExportController, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.ex_export_my_data_first)) },
        text = {
            androidx.compose.foundation.layout.Column {
                ExportFormat.entries.forEach { format ->
                    TextButton(onClick = { dismiss(); controller.export(format) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(format.rowTitle), Modifier.fillMaxWidth(), color = Theme.colors.accent)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
