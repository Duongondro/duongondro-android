package app.duongondro.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import app.duongondro.R
import app.duongondro.model.AppModel
import app.duongondro.ui.CardSection
import app.duongondro.ui.FilledAction
import app.duongondro.ui.card
import app.duongondro.ui.theme.Size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme

/**
 * Settings › Your data: a full export and a full purge, both self-service
 * (GDPR Articles 15, 17 and 20). Nobody has to email a human. The export is laid
 * out as CodeShare's Manage my data: a card with one row per format, what the
 * export holds in the footer.
 */
@Composable
fun YourDataScreen(model: AppModel, openDelete: () -> Unit, back: () -> Unit) {
    val withAccount = hasAccount(model)
    val exports = rememberExportController(model)
    Box {
        Page(stringResource(R.string.section_your_data), back) {
            CardSection(header = stringResource(R.string.ex_export), footer = stringResource(R.string.ex_footer)) {
                ExportRows(exports)
            }
            if (exports.serverMissing) {
                Text(stringResource(R.string.export_server_missing), style = Theme.type.footnote, color = Theme.colors.destructive,
                    modifier = Modifier.padding(horizontal = Space.l))
            }
            CardSection(footer = stringResource(if (withAccount) R.string.delete_footer_account else R.string.delete_footer)) {
                Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).clickable(enabled = !exports.working, onClick = openDelete)
                    .padding(horizontal = Space.l),
                    horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Theme.colors.destructive)
                    Text(stringResource(R.string.delete_everything), Modifier.weight(1f), style = Theme.type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = Theme.colors.destructive)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
                }
            }
        }
        ExportProgress(exports)
    }
    exports.problem?.let { ErrorDialog(stringResource(R.string.export_failed), it) { exports.problem = null } }
}

/** A spinner over the screen while the export is built, as iOS's ProgressView. */
@Composable
private fun BoxScope.ExportProgress(exports: ExportController) {
    if (exports.working) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Theme.colors.accent)
}

/** Typed confirmation, then the purge, then Welcome. */
@Composable
fun DeleteEverythingScreen(model: AppModel, back: () -> Unit) {
    val context = LocalContext.current
    val word = stringResource(R.string.delete_word)
    var typed by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    val exports = rememberExportController(model)
    var choosingFormat by remember { mutableStateOf(false) }
    Box {
    Page(stringResource(R.string.delete_everything), back) {
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(if (hasAccount(model)) R.string.delete_explainer_account else R.string.delete_explainer))
            Text(stringResource(R.string.export_first), color = Theme.colors.muted)
            // As CodeShare's delete confirmation: export first, in any format.
            Text(stringResource(R.string.ex_export_my_data_first), style = Theme.type.body, color = Theme.colors.accent,
                modifier = Modifier.fillMaxWidth().heightIn(min = Size.minTap)
                    .clickable(enabled = !exports.working, role = Role.Button) { choosingFormat = true }.wrapContentHeight(Alignment.CenterVertically))
        }
        OutlinedTextField(typed, { typed = it }, label = { Text(stringResource(R.string.type_to_confirm, word)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth())
        FilledAction(stringResource(R.string.delete_everything), fill = Theme.colors.destructive,
            // Not while an export is being built: the purge would delete what it reads.
            enabled = !exports.working && typed.trim().equals(word, ignoreCase = true)) {
            model.purge(context) { failure = it }
        }
    }
    ExportProgress(exports)
    }
    if (choosingFormat) ExportFirstDialog(exports) { choosingFormat = false }
    exports.problem?.let { ErrorDialog(stringResource(R.string.export_failed), it) { exports.problem = null } }
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
