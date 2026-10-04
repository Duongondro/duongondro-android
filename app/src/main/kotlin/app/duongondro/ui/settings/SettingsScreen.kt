package app.duongondro.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.BuildConfig
import app.duongondro.R
import app.duongondro.model.AppModel
import app.duongondro.ui.card
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(model: AppModel) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().background(Theme.colors.ground).verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xl, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(vertical = Space.s))

        SectionTitle(stringResource(R.string.section_general))
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.mala_counts_as))
            val sizes = listOf(100, 108)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sizes.forEachIndexed { i, size ->
                    SegmentedButton(
                        selected = snapshot.preferences.malaSize == size,
                        onClick = { model.updatePreferences { it.copy(malaSize = size) } },
                        shape = SegmentedButtonDefaults.itemShape(i, sizes.size, MaterialTheme.shapes.small),
                    ) { Text("$size") }
                }
            }
        }

        SectionTitle(stringResource(R.string.section_about))
        About()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Theme.colors.muted, modifier = Modifier.padding(top = Space.m))
}

/**
 * What is running, so anyone can match the app to its source: version, the
 * short commit with -dirty on development builds. Tap opens the commit on
 * GitHub, a long press copies the full hash.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun About() {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val revision = BuildConfig.GIT_REVISION
    val short = (if (revision == "unknown") revision else revision.take(7)) + if (BuildConfig.GIT_DIRTY) "-dirty" else ""
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.version), Modifier.weight(1f))
            Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Theme.colors.muted)
        }
        Row(
            Modifier.fillMaxWidth().combinedClickable(
                onClick = {
                    if (revision != "unknown") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/Duongondro/duongondro-android/commit/$revision".toUri()))
                    }
                },
                onLongClick = { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("commit", revision).toClipEntry()) } },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.source), Modifier.weight(1f))
            Text(short, color = Theme.colors.muted)
        }
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/Duongondro/duongondro-android".toUri()))
            }),
        ) {
            Text(stringResource(R.string.source_code), Modifier.weight(1f), color = Theme.colors.accent)
            Text("BSD-3-Clause", color = Theme.colors.muted)
        }
    }
}
