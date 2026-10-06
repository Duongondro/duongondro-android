package app.duongondro.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.duongondro.R
import app.duongondro.account.Gender
import app.duongondro.ui.Bar
import androidx.compose.ui.draw.clip
import app.duongondro.core.Catalogue
import app.duongondro.core.Practice
import app.duongondro.core.PracticeGroup
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.model.AppModel
import app.duongondro.model.Preferences
import app.duongondro.reminders.rememberNotificationPermission
import app.duongondro.ui.CardSection
import app.duongondro.ui.FilledAction
import app.duongondro.ui.OutlinedAction
import app.duongondro.ui.RowDivider
import app.duongondro.ui.timePickerColors
import app.duongondro.ui.card
import app.duongondro.ui.settings.SwitchRow
import app.duongondro.ui.theme.Radius
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

sealed interface Step {
    data object Welcome : Step
    data object FinishedNgondro : Step
    data object FinishedShortRefuge : Step
    data object Practices : Step
    data class Counts(val index: Int) : Step
    data object Mala : Step
    data object Reminder : Step
    /** "Keep it on this phone, or online?" */
    data object Where : Step
    data object Invite : Step
    data object Consent : Step
    data object Email : Step
    data object CheckEmail : Step
    data object Username : Step
    data object Name : Step
    data object Gender : Step
    data object Passkey : Step
    data object Recovery : Step
    /** Two groups of the recovery code typed back. */
    data object RecoveryCheck : Step
    /** "Welcome back": sign in with a passkey or an emailed link. */
    data object SignIn : Step
    /** Signed in to an account with keys on a new phone: the recovery code brings them back. */
    data object Restore : Step
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

    var finishedNgondro by mutableStateOf(false)
    var finishedShortRefuge by mutableStateOf(false)
    val chosen = mutableStateListOf<Chosen>()
    var malaSize by mutableStateOf(108)
    var reminder by mutableStateOf<LocalTime?>(LocalTime.of(20, 0))

    /** True after "I already have an account": the email steps then sign in rather than sign up. */
    var signingIn by mutableStateOf(false)
    /** The invitation code, from a link opened earlier or typed in. */
    var inviteCode by mutableStateOf("")
    /** Why the invitation was not accepted, shown under its field. */
    var inviteProblem by mutableStateOf<Int?>(null)
    /** The server said the username is taken; shown on the Username step. */
    var usernameTaken by mutableStateOf(false)
    var consented by mutableStateOf(false)
    var email by mutableStateOf("")
    var username by mutableStateOf("")
    var displayName by mutableStateOf("")
    var gender by mutableStateOf<Gender?>(null)
    /** Fetched once, so going back and forward shows the same code. */
    var recoveryCode by mutableStateOf<String?>(null)

    val canGoBack: Boolean get() = history.isNotEmpty()

    private var begun = false

    /** Where a flow from Settings starts, once. */
    fun begin(start: Step, signingIn: Boolean) {
        if (begun) return
        begun = true
        if (start != Step.Welcome) {
            step = start
            this.signingIn = signingIn
        }
    }

    fun go(next: Step) {
        history += step
        step = next
    }

    fun back() {
        history.removeLastOrNull()?.let { step = it }
    }

    /** Back to an earlier step (a taken username, a spent invitation), keeping the answers. */
    fun backTo(target: Step) {
        val i = history.lastIndexOf(target)
        if (i < 0) return go(target)
        while (history.size > i) history.removeAt(history.lastIndex)
        step = target
    }

    val available: List<Practice> get() = Catalogue.available(finishedNgondro, finishedShortRefuge)

    fun isChosen(id: String) = chosen.any { it.practice.id == id }

    fun toggle(p: Practice) {
        val i = chosen.indexOfFirst { it.practice.id == p.id }
        if (i >= 0) chosen.removeAt(i) else chosen += Chosen(p, streakOnly = p.streakOnlyByDefault)
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
            // Signing in skips the practice questions, the reminder's among them.
            reminderMinutes = if (signingIn) null else reminder?.let { it.hour * 60 + it.minute },
        )
        model.perform {
            model.store.completeOnboarding(practices, seeds, prefs)
            model.accounts?.scheduleSync(0)
        }
    }
}

/** How far along the bar a step is, and out of how many: five practice questions, then three account steps. */
private fun Step.progress(signingIn: Boolean): Pair<Int, Int>? = when (this) {
    Step.FinishedNgondro -> 1 to PRACTICE_STEPS
    Step.FinishedShortRefuge -> 2 to PRACTICE_STEPS
    Step.Practices -> 3 to PRACTICE_STEPS
    is Step.Counts, Step.Mala -> 4 to PRACTICE_STEPS
    Step.Reminder -> 5 to PRACTICE_STEPS
    Step.Email, Step.CheckEmail, Step.Username -> if (signingIn) null else 1 to ACCOUNT_STEPS
    Step.Name -> if (signingIn) null else 2 to ACCOUNT_STEPS
    Step.Gender -> if (signingIn) null else 3 to ACCOUNT_STEPS
    else -> null
}

private const val PRACTICE_STEPS = 5
private const val ACCOUNT_STEPS = 3

/**
 * The first run, from Welcome; or, from Settings with [done] set, only the
 * account steps from [start] (sign in again, or make an account later),
 * leaving the practices and preferences as they are.
 */
@Composable
fun OnboardingScreen(model: AppModel, start: Step = Step.Welcome, done: (() -> Unit)? = null) {
    // Keyed by the purge generation: after "Delete everything" a fresh flow
    // starts at Welcome, holding none of the answers that were just deleted.
    val generation by model.generation.collectAsStateWithLifecycle()
    val flow: OnboardingFlow = viewModel(key = if (done != null) "account-$start" else "onboarding-$generation")
    // The account steps need the network layer, which every real model has.
    val accounts = model.accounts ?: return
    LaunchedEffect(flow) { flow.begin(start, signingIn = start == Step.SignIn) }
    val leave = { done?.invoke() ?: Unit }
    BackHandler(enabled = flow.canGoBack || done != null) { if (flow.canGoBack) flow.back() else leave() }
    // Local mode keeps the onboarding's answers; from Settings, nothing about the practices changes.
    val finishLocal = { if (done != null) leave() else flow.finish(model) }
    val finishOnline = {
        if (done != null) {
            model.accounts?.scheduleSync(0)
            leave()
        } else flow.finish(model)
    }
    val ground = if (flow.step == Step.Welcome) Theme.colors.welcomeGround else Theme.colors.ground
    Column(Modifier.fillMaxSize().background(ground).safeDrawingPadding().padding(horizontal = Space.xl)) {
        if (flow.step != Step.Welcome) StepTopBar(flow, if (done != null && !flow.canGoBack) leave else null)
        when (val step = flow.step) {
            Step.Welcome -> Welcome(flow)
            Step.FinishedNgondro -> YesNo(stringResource(R.string.q_finished_ngondro), stringResource(R.string.q_finished_ngondro_detail),
                yes = { flow.finishedNgondro = true; flow.finishedShortRefuge = true; flow.pruneToAvailable(); flow.go(Step.Practices) },
                no = { flow.finishedNgondro = false; flow.go(Step.FinishedShortRefuge) })
            Step.FinishedShortRefuge -> YesNo(stringResource(R.string.q_finished_short_refuge), stringResource(R.string.q_finished_short_refuge_detail),
                yes = { flow.finishedShortRefuge = true; flow.pruneToAvailable(); flow.go(Step.Practices) },
                no = { flow.finishedShortRefuge = false; flow.pruneToAvailable(); flow.go(Step.Practices) })
            Step.Practices -> Practices(flow)
            is Step.Counts -> Counts(flow, step.index)
            Step.Mala -> Choice(stringResource(R.string.q_mala), stringResource(R.string.q_mala_detail), listOf(100, 108)) {
                flow.malaSize = it; flow.go(Step.Reminder)
            }
            Step.Reminder -> Reminder(flow)
            Step.Where -> WhereStep(flow, model, finishLocal)
            Step.Invite -> InviteStep(flow, accounts, finishLocal)
            Step.Consent -> ConsentStep(flow)
            Step.Email -> EmailStep(flow, accounts)
            Step.CheckEmail -> CheckEmailStep(flow, model, accounts, finishOnline)
            Step.Username -> UsernameStep(flow)
            Step.Name -> NameStep(flow)
            Step.Gender -> GenderStep(flow, accounts)
            Step.Passkey -> PasskeyStep(flow, accounts)
            Step.Recovery -> RecoveryStep(flow, accounts, finishOnline)
            Step.RecoveryCheck -> RecoveryCheckStep(flow.recoveryCode.orEmpty(), back = { flow.back() }) {
                accounts.confirmRecoveryCode()
                finishOnline()
            }
            Step.SignIn -> SignInStep(flow, model, accounts, finishOnline)
            Step.Restore -> RestoreStep(accounts, finishOnline)
        }
    }
}

/** A back arrow on the left, the progress bar centred where the step has one. */
@Composable
private fun StepTopBar(flow: OnboardingFlow, leave: (() -> Unit)?) {
    val progress = flow.step.progress(flow.signingIn)
    Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Size.minTap)) {
            IconButton(onClick = { leave?.invoke() ?: flow.back() }, enabled = flow.canGoBack || leave != null, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Theme.colors.ink)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            progress?.let { (reached, total) ->
                val label = if (total == ACCOUNT_STEPS) stringResource(R.string.account_step_n_of_m, reached, total)
                else stringResource(R.string.step_n_of_m, reached, total)
                Bar(reached.toFloat() / total, Size.stepDashHeight,
                    Modifier.width(Size.progressWidth).clip(RoundedCornerShape(Radius.dash)).semantics { contentDescription = label })
            }
        }
        Spacer(Modifier.size(Size.minTap))
    }
}

/** A screen title, as large as a question, with a line of detail under it. */
@Composable
private fun Header(title: String, detail: String? = null, style: TextStyle = Theme.type.header) {
    Column(Modifier.padding(top = Space.s, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = style, color = Theme.colors.ink)
        detail?.let { Text(it, style = Theme.type.secondary, color = Theme.colors.muted) }
    }
}

@Composable
private fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledAction(text, enabled = enabled, onClick = onClick)
}

@Composable
private fun ColumnScope.Welcome(flow: OnboardingFlow) {
    Spacer(Modifier.weight(1f))
    Image(painterResource(R.drawable.emblem), contentDescription = null, modifier = Modifier.width(Size.emblem).align(Alignment.CenterHorizontally))
    Column(Modifier.padding(top = Space.xxl).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(stringResource(R.string.app_name), style = Theme.type.welcomeTitle, color = Theme.colors.welcomeTitle, textAlign = TextAlign.Center)
        Text(stringResource(R.string.welcome_tagline), style = Theme.type.body.copy(fontSize = 18.sp, lineHeight = 25.sp),
            color = Theme.colors.welcomeSoft, textAlign = TextAlign.Center)
        Text(stringResource(R.string.welcome_encrypted), style = Theme.type.subtitle.copy(fontWeight = FontWeight.SemiBold),
            color = Theme.colors.welcomeGoldText, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.weight(1f))
    Column(Modifier.padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        FilledAction(stringResource(R.string.get_started), fill = Theme.colors.welcomePrimary, ink = Theme.colors.welcomePrimaryInk) {
            flow.signingIn = false
            flow.go(Step.FinishedNgondro)
        }
        OutlinedAction(stringResource(R.string.have_account), tint = Theme.colors.welcomeOutlineInk, height = Size.button,
            border = Theme.colors.welcomeOutline, borderWidth = Size.hairline, container = Color.Transparent) {
            flow.signingIn = true
            flow.go(Step.SignIn)
        }
    }
}

/** A yes/no question, centred in the screen, answered by tapping. */
@Composable
private fun ColumnScope.YesNo(title: String, detail: String, yes: () -> Unit, no: () -> Unit) {
    Spacer(Modifier.weight(1f))
    Text(title, style = Theme.type.question, color = Theme.colors.ink)
    Text(detail, style = Theme.type.lead, color = Theme.colors.soft, modifier = Modifier.padding(top = Space.l))
    Spacer(Modifier.weight(1f))
    Column(Modifier.padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        FilledAction(stringResource(R.string.yes), height = Size.answer, onClick = yes)
        OutlinedAction(stringResource(R.string.no), height = Size.answer, border = Theme.colors.buttonOutline, borderWidth = Size.hairline,
            container = Color.Transparent, onClick = no)
    }
}

@Composable
private fun ColumnScope.Choice(title: String, detail: String, options: List<Int>, pick: (Int) -> Unit) {
    Spacer(Modifier.weight(1f))
    Text(title, style = Theme.type.question, color = Theme.colors.ink)
    Text(detail, style = Theme.type.lead, color = Theme.colors.soft, modifier = Modifier.padding(top = Space.l))
    Spacer(Modifier.weight(1f))
    Column(Modifier.padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        options.forEachIndexed { i, v ->
            if (i == 0) FilledAction("$v", height = Size.answer) { pick(v) } else OutlinedAction("$v", height = Size.answer, border = Theme.colors.buttonOutline, borderWidth = Size.hairline,
                container = Color.Transparent) { pick(v) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Practices(flow: OnboardingFlow) {
    var addingCustom by remember { mutableStateOf(false) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Header(stringResource(R.string.q_daily), stringResource(R.string.q_daily_detail))
        CardSection {
            val all = flow.available + flow.chosen.map { it.practice }.filter { it.isCustom }
            all.forEach { p ->
                val i = flow.chosen.indexOfFirst { it.practice.id == p.id }
                val on = i >= 0
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Size.minTap).toggleable(value = on, role = Role.Checkbox) { flow.toggle(p) }
                        .padding(start = Space.s, end = Space.l),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, onCheckedChange = null, modifier = Modifier.padding(Space.m),
                        colors = CheckboxDefaults.colors(checkedColor = Theme.colors.accent, checkmarkColor = Theme.colors.onAccent,
                            uncheckedColor = Theme.colors.inputBorder))
                    FlowRow(Modifier.weight(1f).padding(vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s),
                        verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = Theme.type.body.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal), color = Theme.colors.ink)
                        p.secondName?.let { Text(it, style = Theme.type.secondary, color = Theme.colors.muted) }
                    }
                }
                if (on && p.streakOnlyAllowed) {
                    Box(Modifier.padding(start = Size.minTap + Space.s, end = Space.l, bottom = Space.s)) {
                        SwitchRow(stringResource(R.string.streak_only_switch), null, flow.chosen[i].streakOnly) {
                            flow.chosen[i] = flow.chosen[i].copy(streakOnly = it)
                        }
                    }
                }
                RowDivider()
            }
            Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).clickable { addingCustom = true }.padding(horizontal = Space.l),
                horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Theme.colors.accent, modifier = Modifier.size(Size.checkbox))
                Text(stringResource(R.string.add_your_own), style = Theme.type.body.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.accent)
            }
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
                SwitchRow(stringResource(R.string.streak_only_switch), null, streakOnly) { streakOnly = it }
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

/** One screen per chosen practice: so far, streak and when last practised, an optional later round and longest streak. */
@Composable
private fun ColumnScope.Counts(flow: OnboardingFlow, index: Int) {
    val c = flow.chosen.getOrNull(index) ?: return
    val update = { f: (Chosen) -> Chosen -> flow.chosen[index] = f(flow.chosen[index]) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m + Space.xxs)) {
        Text(stringResource(R.string.practice_n_of_m, index + 1, flow.chosen.size), style = Theme.type.secondary.copy(fontWeight = FontWeight.Bold),
            color = Theme.colors.muted, modifier = Modifier.padding(top = Space.s))
        Text(stringResource(R.string.where_are_you, c.practice.name), style = Theme.type.questionSmall, color = Theme.colors.ink)
        Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                if (!c.streakOnly) {
                    NumberField(stringResource(R.string.count_so_far), c.countSoFar, Modifier.weight(1f)) { v -> update { it.copy(countSoFar = v) } }
                }
                NumberField(stringResource(R.string.current_streak_days), c.streak, Modifier.weight(1f)) { v -> update { it.copy(streak = v) } }
            }
            if (c.streak > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m - Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.last_practised), style = Theme.type.secondary, color = Theme.colors.soft)
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        listOf(true to R.string.today, false to R.string.yesterday).forEachIndexed { i, (value, label) ->
                            SegmentedButton(selected = c.lastWasToday == value, onClick = { update { it.copy(lastWasToday = value) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 2, MaterialTheme.shapes.small),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = Theme.colors.accent, activeContentColor = Theme.colors.onAccent,
                                    activeBorderColor = Theme.colors.accent, inactiveContainerColor = Theme.colors.softFill,
                                    inactiveContentColor = Theme.colors.soft, inactiveBorderColor = Theme.colors.softFill),
                                icon = {}) { Text(stringResource(label), style = Theme.type.secondary.copy(fontWeight = FontWeight.SemiBold)) }
                        }
                    }
                }
            }
        }
        if (!c.streakOnly) {
            if (c.showsRound) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.round_n, c.round), Modifier.weight(1f), style = Theme.type.body, color = Theme.colors.soft)
                    val previous = stringResource(R.string.previous_round)
                    val next = stringResource(R.string.next_round)
                    TextButton(onClick = { update { it.copy(round = maxOf(1, it.round - 1)) } },
                        modifier = Modifier.semantics { contentDescription = previous }) { Text("−") }
                    TextButton(onClick = { update { it.copy(round = minOf(99, it.round + 1)) } },
                        modifier = Modifier.semantics { contentDescription = next }) { Text("+") }
                }
            } else if (c.practice.target != null) {
                TextButton(onClick = { update { it.copy(showsRound = true) } }, modifier = Modifier.heightIn(min = Size.minTap),
                    contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.later_round),
                        style = Theme.type.secondary.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline), color = Theme.colors.accent)
                }
            }
        }
        if (c.streak > 0) {
            NumberField(stringResource(R.string.longest_optional), c.longest ?: 0, Modifier.fillMaxWidth()) { v -> update { it.copy(longest = v.takeIf { it > 0 }) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (!c.streakOnly) Text(stringResource(R.string.count_footer), style = Theme.type.footnote, color = Theme.colors.muted)
            Text(stringResource(R.string.seed_footer), style = Theme.type.footnote, color = Theme.colors.muted)
        }
    }
    Column(Modifier.padding(vertical = Space.l)) {
        Primary(stringResource(R.string.continue_)) {
            flow.go(if (index + 1 < flow.chosen.size) Step.Counts(index + 1) else Step.Mala)
        }
    }
}

/** A labelled whole-number box that shows empty for 0 and keeps ASCII digits only. */
@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s - Space.xxs)) {
        Text(label, style = Theme.type.footnote.copy(fontWeight = FontWeight.Bold), color = Theme.colors.soft)
        OutlinedTextField(
            value = if (value == 0) "" else value.toString(),
            onValueChange = { onChange(it.digits() ?: 0) },
            placeholder = { Text("0", style = Theme.type.field) }, singleLine = true,
            textStyle = Theme.type.field,
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Theme.colors.accent, unfocusedBorderColor = Theme.colors.inputBorder,
                focusedTextColor = Theme.colors.ink, unfocusedTextColor = Theme.colors.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().heightIn(min = Size.field).semantics { contentDescription = label },
        )
    }
}

private fun String.digits(): Int? = filter { it in '0'..'9' }.take(9).toIntOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.Reminder(flow: OnboardingFlow) {
    val initial = flow.reminder ?: LocalTime.of(20, 0)
    val state = rememberTimePickerState(initial.hour, initial.minute)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Header(stringResource(R.string.q_reminder), stringResource(R.string.q_reminder_detail))
        TimePicker(state, colors = timePickerColors())
    }
    val time = LocalTime.of(state.hour, state.minute)
    // Asked with the reason on screen; a refusal still keeps the time for later.
    val askPermission = rememberNotificationPermission { flow.go(Step.Where) }
    Column(Modifier.padding(vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Primary(stringResource(R.string.remind_at_time, DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0]).format(time))) {
            flow.reminder = time
            askPermission()
        }
        OutlinedAction(stringResource(R.string.no_reminders)) { flow.reminder = null; flow.go(Step.Where) }
    }
}
