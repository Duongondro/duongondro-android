package app.duongondro.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.duongondro.R
import app.duongondro.core.Catalogue
import app.duongondro.core.Practice
import app.duongondro.core.PracticeGroup
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.model.AppModel
import app.duongondro.model.Preferences
import app.duongondro.ui.PracticeName
import app.duongondro.ui.card
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

enum class Door { Invite, JustMe, ExistingAccount }

sealed interface Step {
    data object Welcome : Step
    data object FinishedNgondro : Step
    data object FinishedShortRefuge : Step
    data object Practices : Step
    data class Counts(val index: Int) : Step
    data object Mala : Step
    data object Reminder : Step
    data object Door : Step
}

/** What the user said about one chosen practice. */
data class Chosen(
    val practice: Practice,
    val streakOnly: Boolean = false,
    val countSoFar: Int = 0,
    val round: Int = 1,
    val showsRound: Boolean = false,
    val streak: Int = 0,
    val lastWasToday: Boolean = true,
    val longest: Int? = null,
)

/**
 * The practice comes before the account: one question per screen, each with a
 * back step that keeps the answers given so far. Yes/no questions advance on
 * tap. Survives rotation as a ViewModel.
 */
class OnboardingFlow : ViewModel() {
    var step by mutableStateOf<Step>(Step.Welcome)
        private set
    private val history = mutableListOf<Step>()

    var door by mutableStateOf(Door.JustMe)
    var finishedNgondro by mutableStateOf(false)
    var finishedShortRefuge by mutableStateOf(false)
    val chosen = mutableStateListOf<Chosen>()
    var malaSize by mutableStateOf(108)
    var reminder by mutableStateOf<LocalTime?>(LocalTime.of(20, 0))

    val canGoBack: Boolean get() = history.isNotEmpty()

    fun go(next: Step) {
        history += step
        step = next
    }

    fun back() {
        history.removeLastOrNull()?.let { step = it }
    }

    val available: List<Practice> get() = Catalogue.available(finishedNgondro, finishedShortRefuge)

    fun isChosen(id: String) = chosen.any { it.practice.id == id }

    fun toggle(p: Practice) {
        val i = chosen.indexOfFirst { it.practice.id == p.id }
        if (i >= 0) chosen.removeAt(i) else chosen += Chosen(p, streakOnly = p.id == "8th-karmapa")
    }

    /** After the path questions, drop choices the answers no longer allow. */
    fun pruneToAvailable() {
        val allowed = available.map { it.id }.toSet()
        chosen.removeAll { !it.practice.isCustom && it.practice.id !in allowed }
    }

    fun finish(model: AppModel, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val today = civilDate(now, zone)
        val practices = chosen.mapIndexed { i, c ->
            TrackedPractice(
                c.practice, wantsStreakOnly = c.streakOnly,
                openingCount = if (c.streakOnly) 0 else TrackedPractice.openingCount(c.round, c.countSoFar, c.practice.target),
                sortOrder = i,
            )
        }
        val seeds = chosen.filter { it.streak > 0 }.map {
            StreakSeed(it.practice.id, it.streak, it.longest, if (it.lastWasToday) today else today.minusDays(1), zone.id)
        }
        val prefs = Preferences(
            onboarded = true,
            finishedNgondro = finishedNgondro,
            finishedShortRefuge = finishedShortRefuge || finishedNgondro,
            malaSize = malaSize,
            reminderMinutes = reminder?.let { it.hour * 60 + it.minute },
        )
        model.perform { model.store.completeOnboarding(practices, seeds, prefs) }
    }
}

@Composable
fun OnboardingScreen(model: AppModel, flow: OnboardingFlow = viewModel()) {
    BackHandler(enabled = flow.canGoBack) { flow.back() }
    val ground = if (flow.step == Step.Welcome) Theme.colors.welcomeGround else Theme.colors.ground
    Column(Modifier.fillMaxSize().background(ground).safeDrawingPadding().padding(horizontal = Space.xl)) {
        if (flow.canGoBack) {
            IconButton(onClick = { flow.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        }
        when (val step = flow.step) {
            Step.Welcome -> Welcome(flow)
            Step.FinishedNgondro -> YesNo(stringResource(R.string.q_finished_ngondro),
                yes = { flow.finishedNgondro = true; flow.finishedShortRefuge = true; flow.pruneToAvailable(); flow.go(Step.Practices) },
                no = { flow.finishedNgondro = false; flow.go(Step.FinishedShortRefuge) })
            Step.FinishedShortRefuge -> YesNo(stringResource(R.string.q_finished_short_refuge),
                yes = { flow.finishedShortRefuge = true; flow.pruneToAvailable(); flow.go(Step.Practices) },
                no = { flow.finishedShortRefuge = false; flow.pruneToAvailable(); flow.go(Step.Practices) })
            Step.Practices -> Practices(flow)
            is Step.Counts -> Counts(flow, step.index)
            Step.Mala -> Choice(stringResource(R.string.q_mala), stringResource(R.string.q_mala_detail), listOf(100, 108)) {
                flow.malaSize = it; flow.go(Step.Reminder)
            }
            Step.Reminder -> Reminder(flow)
            Step.Door -> DoorStep(flow) { flow.finish(model) }
        }
    }
}

@Composable
private fun Header(title: String, detail: String? = null) {
    Column(Modifier.padding(top = Space.l, bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        detail?.let { Text(it, color = Theme.colors.muted) }
    }
}

@Composable
private fun Primary(text: String, fill: Color = Theme.colors.accent, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = Theme.colors.onAccent),
        modifier = Modifier.fillMaxWidth().heightIn(min = Space.button),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun Secondary(text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Theme.colors.streakCard, contentColor = Theme.colors.accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = Space.button),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun ColumnScope.Welcome(flow: OnboardingFlow) {
    Spacer(Modifier.weight(1f))
    Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.accent, modifier = Modifier.size(Space.bigButton))
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, color = Theme.colors.accent)
    Text(stringResource(R.string.welcome_line), style = MaterialTheme.typography.titleLarge.copy(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily),
        color = Theme.colors.muted, modifier = Modifier.padding(top = Space.m))
    Spacer(Modifier.weight(1f))
    Column(Modifier.padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        val pick = { d: Door -> flow.door = d; flow.go(Step.FinishedNgondro) }
        Primary(stringResource(R.string.door_invite), fill = Theme.colors.welcomePrimary) { pick(Door.Invite) }
        Secondary(stringResource(R.string.door_just_me)) { pick(Door.JustMe) }
        Secondary(stringResource(R.string.door_account)) { pick(Door.ExistingAccount) }
    }
}

@Composable
private fun YesNo(title: String, yes: () -> Unit, no: () -> Unit) {
    Header(title)
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Secondary(stringResource(R.string.yes), yes)
        Secondary(stringResource(R.string.no), no)
    }
}

@Composable
private fun Choice(title: String, detail: String, options: List<Int>, pick: (Int) -> Unit) {
    Header(title, detail)
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        options.forEach { Secondary("$it") { pick(it) } }
    }
}

@Composable
private fun ColumnScope.Practices(flow: OnboardingFlow) {
    var addingCustom by remember { mutableStateOf(false) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Header(stringResource(R.string.q_daily), stringResource(R.string.q_daily_detail))
        (flow.available + flow.chosen.map { it.practice }.filter { it.isCustom }).forEach { p ->
            val i = flow.chosen.indexOfFirst { it.practice.id == p.id }
            Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(
                    Modifier.fillMaxWidth().semantics { selected = i >= 0 }.clickable { flow.toggle(p) },
                    horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (i >= 0) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Theme.colors.accent)
                    else Icon(painterResource(R.drawable.ic_circle), contentDescription = null, tint = Theme.colors.muted)
                    PracticeName(p)
                }
                if (i >= 0 && p.streakOnlyAllowed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.streak_only_switch), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(flow.chosen[i].streakOnly, { flow.chosen[i] = flow.chosen[i].copy(streakOnly = it) })
                    }
                }
            }
        }
        TextButton(onClick = { addingCustom = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.add_your_own), Modifier.padding(start = Space.s))
        }
    }
    Column(Modifier.padding(vertical = Space.l)) {
        Primary(stringResource(R.string.continue_), enabled = flow.chosen.isNotEmpty()) { flow.go(Step.Counts(0)) }
    }
    if (addingCustom) {
        CustomPracticeDialog(onDismiss = { addingCustom = false }) { p, streakOnly ->
            addingCustom = false
            flow.chosen += Chosen(p, streakOnly = streakOnly)
        }
    }
}

/** A custom practice: a name, a target or none, and the streak-only switch. */
@Composable
fun CustomPracticeDialog(onDismiss: () -> Unit, onAdd: (Practice, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var streakOnly by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.your_own_practice)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.streak_only_switch), Modifier.weight(1f))
                    Switch(streakOnly, { streakOnly = it })
                }
                if (!streakOnly) {
                    OutlinedTextField(target, { target = it }, label = { Text(stringResource(R.string.target_optional)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val goal = target.digits()?.takeIf { it > 0 }
                onAdd(Practice("custom-${UUID.randomUUID()}", name.trim(), null, PracticeGroup.AnyTime,
                    if (streakOnly) null else goal, allowStreakOnly = true, isCustom = true), streakOnly)
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One screen per chosen practice: count so far, later round, streak, longest. */
@Composable
private fun ColumnScope.Counts(flow: OnboardingFlow, index: Int) {
    val c = flow.chosen.getOrNull(index) ?: return
    val update = { f: (Chosen) -> Chosen -> flow.chosen[index] = f(flow.chosen[index]) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(stringResource(R.string.practice_n_of_m, index + 1, flow.chosen.size), color = Theme.colors.muted,
            modifier = Modifier.padding(top = Space.l))
        PracticeName(c.practice, modifier = Modifier.fillMaxWidth().card())
        if (!c.streakOnly) {
            Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                NumberField(stringResource(R.string.count_so_far), c.countSoFar) { v -> update { it.copy(countSoFar = v) } }
                if (c.showsRound) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.round_n, c.round), Modifier.weight(1f))
                        TextButton(onClick = { update { it.copy(round = maxOf(1, it.round - 1)) } }) { Text("−") }
                        TextButton(onClick = { update { it.copy(round = minOf(99, it.round + 1)) } }) { Text("+") }
                    }
                } else if (c.practice.target != null) {
                    TextButton(onClick = { update { it.copy(showsRound = true) } }) { Text(stringResource(R.string.later_round)) }
                }
            }
            Text(stringResource(R.string.count_footer), style = MaterialTheme.typography.bodySmall, color = Theme.colors.muted)
        }
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            NumberField(stringResource(R.string.current_streak_days), c.streak) { v -> update { it.copy(streak = v) } }
            if (c.streak > 0) {
                Text(stringResource(R.string.last_practised), style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true to R.string.today, false to R.string.yesterday).forEachIndexed { i, (value, label) ->
                        SegmentedButton(selected = c.lastWasToday == value, onClick = { update { it.copy(lastWasToday = value) } },
                            shape = SegmentedButtonDefaults.itemShape(i, 2, MaterialTheme.shapes.small)) { Text(stringResource(label)) }
                    }
                }
                NumberField(stringResource(R.string.longest_optional), c.longest ?: 0) { v -> update { it.copy(longest = v.takeIf { it > 0 }) } }
            }
        }
        Text(stringResource(R.string.seed_footer), style = MaterialTheme.typography.bodySmall, color = Theme.colors.muted)
    }
    Column(Modifier.padding(vertical = Space.l)) {
        Primary(stringResource(R.string.continue_)) {
            flow.go(if (index + 1 < flow.chosen.size) Step.Counts(index + 1) else Step.Mala)
        }
    }
}

/** A whole-number field that shows empty for 0 and keeps ASCII digits only. */
@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { onChange(it.digits() ?: 0) },
        label = { Text(label) }, placeholder = { Text("0") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun String.digits(): Int? = filter { it in '0'..'9' }.take(9).toIntOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.Reminder(flow: OnboardingFlow) {
    val initial = flow.reminder ?: LocalTime.of(20, 0)
    val state = rememberTimePickerState(initial.hour, initial.minute)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Header(stringResource(R.string.q_reminder), stringResource(R.string.q_reminder_detail))
        TimePicker(state)
    }
    val time = LocalTime.of(state.hour, state.minute)
    Column(Modifier.padding(vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Primary(stringResource(R.string.remind_at_time, DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(time))) {
            flow.reminder = time; flow.go(Step.Door)
        }
        Secondary(stringResource(R.string.no_reminders)) { flow.reminder = null; flow.go(Step.Door) }
    }
}

/**
 * Where the doors part. Accounts and friends need the server (phase 3); until
 * then every door ends in local mode, said plainly rather than faked.
 */
@Composable
private fun ColumnScope.DoorStep(flow: OnboardingFlow, finish: () -> Unit) {
    if (flow.door == Door.JustMe) Header(stringResource(R.string.local_title), stringResource(R.string.local_detail))
    else Header(stringResource(R.string.no_accounts_title), stringResource(R.string.no_accounts_detail))
    Spacer(Modifier.weight(1f))
    Column(Modifier.padding(vertical = Space.l)) { Primary(stringResource(R.string.start_practising), onClick = finish) }
}

