package app.duongondro.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.duongondro.account.AccountStatus
import app.duongondro.core.export.ExportContent
import app.duongondro.data.ExportFiles
import app.duongondro.data.ExportFormat
import java.io.File
import java.time.Instant
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import app.duongondro.BuildConfig
import app.duongondro.R
import app.duongondro.data.DataExport
import app.duongondro.model.AppModel
import app.duongondro.ui.CardSection
import app.duongondro.ui.RowDivider
import app.duongondro.ui.FilledAction
import app.duongondro.ui.card
import app.duongondro.ui.theme.Size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Your data: a full export and a full purge, both self-service
 * (GDPR Articles 15, 17 and 20). Nobody has to email a human.
 */
@Composable
fun YourDataScreen(model: AppModel, openDelete: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    val withAccount = hasAccount(model)
    var exporting by remember { mutableStateOf(false) }
    // The export went out without the server's part.
    var serverMissing by remember { mutableStateOf(false) }
    // The made file, waiting for the place the person picks to save it.
    var pending by remember { mutableStateOf<ByteArray?>(null) }

    fun picked(uri: Uri?) {
        val bytes = pending ?: return
        pending = null
        if (uri == null) return // the picker was cancelled
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream") }
            }
            written.exceptionOrNull()?.let { failure = it.message ?: it.toString() }
        }
    }
    // One picker per format, as CodeShare's: the document's type is fixed when the picker is registered.
    val savers = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime), ::picked)
    }

    fun start(format: ExportFormat) {
        if (exporting) return
        exporting = true
        serverMissing = false
        scope.launch {
            try {
                val now = Instant.now()
                pending = export(context, model, format, now) { serverMissing = true }
                savers.getValue(format).launch(format.fileName(now))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            } finally {
                exporting = false
            }
        }
    }

    Page(stringResource(R.string.section_your_data), back) {
        val footer = when {
            exporting -> R.string.export_preparing
            serverMissing -> R.string.export_server_missing
            else -> R.string.export_footer_formats
        }
        CardSection(header = stringResource(R.string.export_section), footer = stringResource(footer)) {
            ExportRow(stringResource(R.string.export_as_pdf), enabled = !exporting) { start(ExportFormat.PDF) }
            RowDivider()
            ExportRow(stringResource(R.string.export_as_json), enabled = !exporting) { start(ExportFormat.JSON) }
            RowDivider()
            ExportRow(stringResource(R.string.export_as_csv), enabled = !exporting) { start(ExportFormat.CSV) }
        }
        CardSection(footer = stringResource(if (withAccount) R.string.delete_footer_account else R.string.delete_footer)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).clickable(onClick = openDelete).padding(horizontal = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Theme.colors.destructive)
                Text(stringResource(R.string.delete_everything), Modifier.weight(1f), style = Theme.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = Theme.colors.destructive)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
            }
        }
    }
    failure?.let { ErrorDialog(stringResource(R.string.export_failed), it) { failure = null } }
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

/**
 * Makes the export in [format], to be saved where the person picks. The open undo window closes first, so every format
 * holds final sessions only; with a ready account the server's part comes next, and an
 * account offline or signed out exports the phone's part and says so ([onServerMissing]).
 */
private suspend fun export(context: Context, model: AppModel, format: ExportFormat, now: Instant, onServerMissing: () -> Unit): ByteArray {
    model.finalisePendingNow()
    val accounts = model.accounts
    val server = try {
        accounts?.serverExport()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        onServerMissing()
        null
    }
    // An account that is signed out or still without keys cannot be asked either.
    val accountWithoutServer = server == null && accounts?.hasAccount() == true
    if (accountWithoutServer) onServerMissing()
    val known = accounts?.knownSessionIds().orEmpty()
    val snapshot = model.snapshot.value
    return withContext(Dispatchers.IO) {
        when (format) {
            ExportFormat.JSON -> DataExport.zip(snapshot, server, known, accountWithoutServer, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", now)
            ExportFormat.PDF, ExportFormat.CSV -> {
                val state = accounts?.state?.value
                val account = state?.takeIf { it.status != AccountStatus.NONE }?.let {
                    ExportContent.AccountFacts(it.displayName, it.username, it.gender?.wire, listOfNotNull(it.email), null)
                }
                val friends = accounts?.friends?.value?.friends.orEmpty().map { ExportContent.FriendRow(it.user, it.displayName, it.gender, null) }
                val content = ExportFiles.content(context, snapshot, server, known, account, friends, now)
                if (format == ExportFormat.PDF) ExportFiles.pdf(context, content, buildLabel(), now) else ExportFiles.csv(context, content, now)
            }
        }
    }
}

/** "0.4 (3f9c2e1)", with -dirty on a development build, as Settings shows it. */
private fun buildLabel(): String {
    val revision = BuildConfig.GIT_REVISION
    val short = (if (revision == "unknown") revision else revision.take(7)) + if (BuildConfig.GIT_DIRTY) "-dirty" else ""
    return "${BuildConfig.VERSION_NAME} ($short)"
}

/** Typed confirmation, then the purge, then Welcome. */
@Composable
fun DeleteEverythingScreen(model: AppModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val word = stringResource(R.string.delete_word)
    var typed by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    Page(stringResource(R.string.delete_everything), back) {
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(if (hasAccount(model)) R.string.delete_explainer_account else R.string.delete_explainer))
            Text(stringResource(R.string.export_first), color = Theme.colors.muted)
        }
        OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.type_to_confirm, word)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth())
        FilledAction(stringResource(R.string.delete_everything), fill = Theme.colors.destructive, enabled = typed.trim().equals(word, ignoreCase = true)) {
            model.purge(context) { failure = it }
        }
    }
    failure?.let { ErrorDialog(stringResource(R.string.could_not_delete), it) { failure = null } }
}

/**
 * Whether "Delete everything" also deletes an account: on the server, and so
 * on the person's other phones at their next sync. A phone signed out of its
 * account must sign in again before it can delete, so it counts too.
 */
@Composable
private fun hasAccount(model: AppModel): Boolean {
    val status = model.accounts?.state?.collectAsStateWithLifecycle()?.value?.status
    return status != null && status != app.duongondro.account.AccountStatus.NONE
}

@Composable
private fun ErrorDialog(title: String, message: String, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.ok)) } })
}
