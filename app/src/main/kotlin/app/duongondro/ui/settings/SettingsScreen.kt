package app.duongondro.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.BuildConfig
import app.duongondro.R
import app.duongondro.core.Catalogue
import app.duongondro.core.TrackedPractice
import app.duongondro.model.AppModel
import app.duongondro.ui.PracticeName
import app.duongondro.ui.card
import app.duongondro.ui.onboarding.CustomPracticeDialog
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import app.duongondro.reminders.Reminders
import app.duongondro.reminders.rememberNotificationPermission

/** The eight launch languages (design: Localisation). */
private val LANGUAGES = listOf("en", "de", "ru", "uk", "pl", "cs", "sk", "hu")

@Composable
fun SettingsScreen(model: AppModel, openPractice: (String) -> Unit, openArchived: () -> Unit, openYourData: () -> Unit,
                   openContributors: () -> Unit, openLicences: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Page(stringResource(R.string.settings)) {
        SectionTitle(stringResource(R.string.section_practices))
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            snapshot.activePractices.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { openPractice(p.id) }, verticalAlignment = Alignment.CenterVertically) {
                    PracticeName(p.practice, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
                }
                HorizontalDivider(color = Theme.colors.cardBorder)
            }
            Row(Modifier.fillMaxWidth().clickable { adding = true }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Theme.colors.accent)
                Text(stringResource(R.string.add_practice), color = Theme.colors.accent, modifier = Modifier.padding(start = Space.s))
            }
            val archived = snapshot.practices.count { it.archived }
            if (archived > 0) {
                Row(Modifier.fillMaxWidth().clickable(onClick = openArchived), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.archived_n, archived), Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
                }
            }
        }

        SectionTitle(stringResource(R.string.section_general))
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            LanguageRow()
            HorizontalDivider(color = Theme.colors.cardBorder)
            Text(stringResource(R.string.mala_counts_as))
            MalaPicker(snapshot.preferences.malaSize, null) { v -> model.updatePreferences { it.copy(malaSize = v ?: 108) } }
            HorizontalDivider(color = Theme.colors.cardBorder)
            ReminderRows(model, snapshot.preferences.reminderMinutes)
            HorizontalDivider(color = Theme.colors.cardBorder)
            SwitchRow(stringResource(R.string.discreet), stringResource(R.string.discreet_detail), snapshot.preferences.discreetNotifications) { v ->
                model.updatePreferences { it.copy(discreetNotifications = v) }
            }
        }

        SectionTitle(stringResource(R.string.section_your_data))
        Row(Modifier.fillMaxWidth().clickable(onClick = openYourData).card(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.export_and_delete), Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
        }

        SectionTitle(stringResource(R.string.section_about))
        About(openContributors, openLicences)
    }
    if (adding) AddPracticeDialog(model) { adding = false }
}

/** A scrolling settings page on the warm ground, with an optional back arrow. */
@Composable
fun Page(title: String, back: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Theme.colors.ground)) {
        back?.let { IconButton(onClick = it, modifier = Modifier.padding(Space.xs)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        } }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl).padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(title, style = if (back == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = Space.s))
            content()
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Theme.colors.muted, modifier = Modifier.padding(top = Space.m))
}

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, so the label is a target too and TalkBack reads one control.
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.m)) {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Theme.colors.muted) }
        }
        Switch(checked, onCheckedChange = null)
    }
}

/** The evening streak-at-risk reminder: on or off, and its time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderRows(model: AppModel, minutes: Int?) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Reminders.canNotify(context)) }
    // Re-checked on return, since the user may change it in system Settings.
    LifecycleResumeEffect(Unit) {
        allowed = Reminders.canNotify(context)
        onPauseOrDispose { }
    }
    val ask = rememberNotificationPermission { allowed = Reminders.canNotify(context) }
    var picking by remember { mutableStateOf(false) }
    SwitchRow(stringResource(R.string.evening_reminder), null, minutes != null) { on ->
        model.updatePreferences { it.copy(reminderMinutes = if (on) 20 * 60 else null) }
        if (on && !allowed) ask()
    }
    if (minutes != null) {
        val time = LocalTime.of(minutes / 60, minutes % 60)
        Row(Modifier.fillMaxWidth().clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.time), Modifier.weight(1f))
            Text(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(time), color = Theme.colors.muted)
        }
        if (!allowed) Text(stringResource(R.string.notifications_off), style = MaterialTheme.typography.bodySmall, color = Theme.colors.destructive)
        if (picking) {
            val state = rememberTimePickerState(time.hour, time.minute)
            AlertDialog(
                onDismissRequest = { picking = false },
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton(onClick = {
                        picking = false
                        model.updatePreferences { it.copy(reminderMinutes = state.hour * 60 + state.minute) }
                    }) { Text(stringResource(R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/** 100 or 108; with `default`, a third choice "Default (n)" that stores null. */
@Composable
private fun MalaPicker(selected: Int?, default: Int?, pick: (Int?) -> Unit) {
    val options: List<Pair<Int?, String>> = buildList {
        default?.let { add(null to stringResource(R.string.mala_default, it)) }
        add(100 to "100")
        add(108 to "108")
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(selected = selected == value, onClick = { pick(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size, MaterialTheme.shapes.small)) { Text(label) }
        }
    }
}

/**
 * In-app language picker: AppCompat per-app locales, stored by AppCompat on
 * API 28–32 and matching the system's per-app setting on 33+. Each language
 * appears in its own name from CLDR, never a hard-coded list of names.
 */
@Composable
private fun LanguageRow() {
    var open by remember { mutableStateOf(false) }
    val current = AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language
    Row(Modifier.fillMaxWidth().clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.language), Modifier.weight(1f))
        Text(current?.let(::nativeName) ?: stringResource(R.string.system_default), color = Theme.colors.muted)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column {
                    (listOf<String?>(null) + LANGUAGES).forEach { code ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                                open = false
                                AppCompatDelegate.setApplicationLocales(
                                    code?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList())
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current == code, onClick = null)
                            Text(code?.let(::nativeName) ?: stringResource(R.string.system_default), Modifier.padding(start = Space.s))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun nativeName(code: String): String {
    val locale = Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale)
}

/** Add any built-in practice the path allows, or a custom one, at any time. */
@Composable
private fun AddPracticeDialog(model: AppModel, close: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(false) }
    val tracked = snapshot.practices.map { it.id }.toSet()
    val prefs = snapshot.preferences
    val options = Catalogue.available(prefs.finishedNgondro, prefs.finishedShortRefuge).filter { it.id !in tracked }
    val add = { p: TrackedPractice ->
        model.save(p.copy(sortOrder = (snapshot.practices.maxOfOrNull { it.sortOrder } ?: -1) + 1))
        close()
    }
    if (custom) {
        CustomPracticeDialog(onDismiss = close) { p, streakOnly -> add(TrackedPractice(p, wantsStreakOnly = streakOnly)) }
        return
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.add_practice)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                options.forEach { p ->
                    PracticeName(p, modifier = Modifier.fillMaxWidth().clickable { add(TrackedPractice(p, wantsStreakOnly = p.streakOnlyByDefault)) })
                }
                Row(Modifier.fillMaxWidth().clickable { custom = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Theme.colors.accent)
                    Text(stringResource(R.string.your_own_practice), color = Theme.colors.accent, modifier = Modifier.padding(start = Space.s))
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One practice's own settings: name (custom), streak-only, target, mala override, archive. */
@Composable
fun PracticeSettingsScreen(model: AppModel, practiceId: String, back: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val p = snapshot.practices.firstOrNull { it.id == practiceId } ?: return
    val save = { q: TrackedPractice -> model.save(q) }
    Page(p.practice.name, back) {
        if (p.practice.isCustom) {
            DebouncedField(p.practice.name, stringResource(R.string.name), numeric = false) { v ->
                if (v.isNotBlank()) save(p.copy(practice = p.practice.copy(name = v.trim())))
            }
        } else {
            PracticeName(p.practice, modifier = Modifier.fillMaxWidth().card())
        }
        if (p.practice.streakOnlyAllowed) {
            Column(Modifier.fillMaxWidth().card()) {
                SwitchRow(stringResource(R.string.streak_only_switch), stringResource(R.string.streak_only_kept), p.streakOnly) {
                    save(p.copy(wantsStreakOnly = it))
                }
            }
        }
        if (!p.streakOnly) {
            SectionTitle(stringResource(R.string.counting))
            Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                DebouncedField(p.practice.target?.toString().orEmpty(), stringResource(R.string.target_per_round), numeric = true) { v ->
                    save(p.copy(practice = p.practice.copy(target = v.filter { it in '0'..'9' }.take(9).toIntOrNull()?.takeIf { it > 0 })))
                }
                Text(stringResource(R.string.mala_counts_as))
                MalaPicker(p.practice.malaSize, snapshot.preferences.malaSize) { v -> save(p.copy(practice = p.practice.copy(malaSize = v))) }
            }
        }
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            TextButton(onClick = {
                save(p.copy(archived = !p.archived))
                if (!p.archived) back()
            }) { Text(stringResource(if (p.archived) R.string.unarchive else R.string.archive)) }
            Text(stringResource(R.string.archive_detail), style = MaterialTheme.typography.bodySmall, color = Theme.colors.muted)
        }
    }
}

/**
 * Typing stays in local state, so the cursor never jumps and a field can be
 * emptied to retype; the value is saved half a second after the last change,
 * one write instead of one per keystroke.
 */
@Composable
private fun DebouncedField(initial: String, label: String, numeric: Boolean, save: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    LaunchedEffect(text) {
        if (text == initial) return@LaunchedEffect
        delay(500)
        save(text)
    }
    OutlinedTextField(
        text, { text = if (numeric) it.filter { c -> c in '0'..'9' }.take(9) else it },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ArchivedScreen(model: AppModel, openPractice: (String) -> Unit, back: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    Page(stringResource(R.string.archived), back) {
        snapshot.practices.filter { it.archived }.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { openPractice(p.id) }.card(), verticalAlignment = Alignment.CenterVertically) {
                PracticeName(p.practice, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
            }
        }
    }
}

/**
 * What is running, so anyone can match the app to its source: version, the
 * short commit with -dirty on development builds. Tap opens the commit on
 * GitHub, a long press copies the full hash.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun About(openContributors: () -> Unit, openLicences: () -> Unit) {
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
                        context.openUrl(Intent(Intent.ACTION_VIEW, "https://github.com/Duongondro/duongondro-android/commit/$revision".toUri()))
                    }
                },
                onLongClick = { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("commit", revision).toClipEntry()) } },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.source), Modifier.weight(1f))
            Text(short, color = Theme.colors.muted)
        }
        Row(Modifier.fillMaxWidth().clickable {
            context.openUrl(Intent(Intent.ACTION_VIEW, "https://github.com/Duongondro/duongondro-android".toUri()))
        }) {
            Text(stringResource(R.string.source_code), Modifier.weight(1f), color = Theme.colors.accent)
            Text("BSD-3-Clause", color = Theme.colors.muted)
        }
        listOf(R.string.contributors to openContributors, R.string.licences to openLicences).forEach { (label, open) ->
            Row(Modifier.fillMaxWidth().clickable(onClick = open), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
            }
        }
    }
}

@Composable
fun ContributorsScreen(back: () -> Unit) {
    val context = LocalContext.current
    val names = remember {
        runCatching {
            val text = context.assets.open("contributors.json").bufferedReader().readText()
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(text)
        }.getOrDefault(emptyList())
    }
    Page(stringResource(R.string.contributors), back) {
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            names.forEach { Text(it) }
        }
        Text(stringResource(R.string.contributors_footer), style = MaterialTheme.typography.bodySmall, color = Theme.colors.muted)
    }
}

@Composable
fun LicencesScreen(back: () -> Unit) {
    val context = LocalContext.current
    val entries = listOf(
        Triple("Duongöndro for Android", "BSD-3-Clause", "https://github.com/Duongondro/duongondro-android/blob/main/LICENSE"),
        Triple("AndroidX, Jetpack Compose, Material 3", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
        Triple("Kotlin, kotlinx.serialization", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
        Triple("Material Symbols", "Apache-2.0", "https://github.com/google/material-design-icons/blob/master/LICENSE"),
        Triple("IBM Plex Sans", "SIL Open Font License 1.1", "https://github.com/IBM/plex/blob/master/LICENSE.txt"),
    )
    Page(stringResource(R.string.licences), back) {
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            entries.forEach { (name, licence, url) ->
                Column(Modifier.fillMaxWidth().clickable { context.openUrl(Intent(Intent.ACTION_VIEW, url.toUri())) }) {
                    Text(name)
                    Text(licence, style = MaterialTheme.typography.bodySmall, color = Theme.colors.muted)
                }
            }
        }
    }
}

/** Opens a link; a phone with no browser shows nothing rather than crashing. */
private fun android.content.Context.openUrl(intent: Intent) {
    runCatching { startActivity(intent) }
}
