package app.duongondro.ui.settings

import android.content.Intent
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.FileProvider
import app.duongondro.BuildConfig
import app.duongondro.R
import app.duongondro.data.DataExport
import app.duongondro.data.Exports
import app.duongondro.model.AppModel
import app.duongondro.ui.CardSection
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
    var sheet by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // The export went out without the server's part.
    var serverMissing by remember { mutableStateOf(false) }
    Page(stringResource(R.string.section_your_data), back) {
        CardSection(footer = stringResource(if (serverMissing) R.string.export_server_missing else R.string.export_footer_formats)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).clickable(enabled = !exporting) { sheet = true }.padding(horizontal = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = Theme.colors.accent)
                Text(stringResource(R.string.export_all), style = Theme.type.body.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.accent)
            }
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
    if (sheet) {
        ExportSheet(exporting, dismiss = { if (!exporting) sheet = false }) { format ->
            exporting = true
            serverMissing = false
            scope.launch {
                try {
                    val file = export(context, model, format) { serverMissing = true }
                    sheet = false
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
                    val send = Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: e.toString()
                } finally {
                    exporting = false
                }
            }
        }
    }
    failure?.let { ErrorDialog(stringResource(R.string.export_failed), it) { failure = null } }
}

/**
 * Makes the export file in [format]. The open undo window closes first, so every format
 * holds final sessions only; with a ready account the server's part comes next, and an
 * account offline or signed out exports the phone's part and says so ([onServerMissing]).
 */
private suspend fun export(context: Context, model: AppModel, format: ExportFormat, onServerMissing: () -> Unit): File {
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
        val now = Instant.now()
        val bytes = when (format) {
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
        Exports.write(context, bytes, format.fileName(now))
    }
}

/** "0.4 (3f9c2e1)", with -dirty on a development build, as Settings shows it. */
private fun buildLabel(): String {
    val revision = BuildConfig.GIT_REVISION
    val short = (if (revision == "unknown") revision else revision.take(7)) + if (BuildConfig.GIT_DIRTY) "-dirty" else ""
    return "${BuildConfig.VERSION_NAME} ($short)"
}

/**
 * The Export sheet (mockup "Android: Export my data"): PDF, CSV or JSON, one line each on
 * what it is for, a note that the counts are decrypted on this phone, then Export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(exporting: Boolean, dismiss: () -> Unit, export: (ExportFormat) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Theme.colors.ground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(stringResource(R.string.export_sheet_title), style = Theme.type.sheetTitle, color = Theme.colors.ink)
            val label = stringResource(R.string.export_format)
            Column(Modifier.selectableGroup().semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(Space.s)) {
                for ((format, title, detail) in listOf(
                    Triple(ExportFormat.PDF, R.string.export_pdf, R.string.export_pdf_detail),
                    Triple(ExportFormat.CSV, R.string.export_csv, R.string.export_csv_detail),
                    Triple(ExportFormat.JSON, R.string.export_json, R.string.export_json_detail),
                )) {
                    val selected = chosen == format
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = Size.answer).clip(MaterialTheme.shapes.medium).background(Theme.colors.card)
                            .border(Size.inputBorder, if (selected) Theme.colors.accent else Theme.colors.inputBorder, MaterialTheme.shapes.medium)
                            .selectable(selected = selected, enabled = !exporting, role = Role.RadioButton) { chosen = format }
                            .padding(horizontal = Space.l, vertical = Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.xxs),
                    ) {
                        Text(stringResource(title), style = Theme.type.body.copy(fontWeight = FontWeight.Bold), color = Theme.colors.ink)
                        Text(stringResource(detail), style = Theme.type.secondary, color = Theme.colors.muted)
                    }
                }
            }
            Text(stringResource(R.string.export_sheet_note), style = Theme.type.secondary, color = Theme.colors.soft)
            Box(contentAlignment = Alignment.Center) {
                FilledAction(stringResource(R.string.export_action), Modifier.fillMaxWidth(), height = Size.secondary, enabled = !exporting) { export(chosen) }
                if (exporting) CircularProgressIndicator(Modifier.size(Size.ring), color = Theme.colors.onAccent, strokeWidth = Size.ringStroke)
            }
        }
    }
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
