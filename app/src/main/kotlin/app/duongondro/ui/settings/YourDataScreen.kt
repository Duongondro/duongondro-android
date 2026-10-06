package app.duongondro.ui.settings

import android.content.Intent
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
import app.duongondro.data.Covers
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
    Page(stringResource(R.string.section_your_data), back) {
        CardSection(footer = stringResource(R.string.export_footer)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).clickable {
                scope.launch {
                    try {
                        // Anything still in the undo window belongs in the export.
                        model.commitPendingNow()
                        val file = withContext(Dispatchers.IO) {
                            val zip = DataExport.zip(model.snapshot.value, null, Covers.all(context),
                                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            Exports.write(context, zip, DataExport.fileName())
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
                        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, null))
                    } catch (e: Exception) {
                        failure = e.message ?: e.toString()
                    }
                }
            }.padding(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = Theme.colors.accent)
                Text(stringResource(R.string.export_all), style = Theme.type.body.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.accent)
            }
        }
        CardSection(footer = stringResource(R.string.delete_footer)) {
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
            Text(stringResource(R.string.delete_explainer))
            val status = model.accounts?.state?.collectAsStateWithLifecycle()?.value?.status
            if (status != null && status != app.duongondro.account.AccountStatus.NONE) Text(stringResource(R.string.delete_server))
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

@Composable
private fun ErrorDialog(title: String, message: String, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.ok)) } })
}
