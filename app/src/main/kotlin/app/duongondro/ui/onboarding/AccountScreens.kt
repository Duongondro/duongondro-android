package app.duongondro.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import app.duongondro.R
import app.duongondro.account.AccountService
import app.duongondro.account.Crockford
import app.duongondro.account.Gender
import app.duongondro.account.InviteCheck
import app.duongondro.account.Passkey
import app.duongondro.account.RedeemResult
import app.duongondro.model.AppModel
import app.duongondro.ui.FilledAction
import app.duongondro.ui.OutlinedAction
import app.duongondro.ui.TextAction
import app.duongondro.ui.theme.Radius
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One call to the [AccountService] in flight: buttons disable while it runs, [failed] says it did not finish. */
private class Call(private val scope: kotlinx.coroutines.CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
}

@Composable
private fun rememberCall(): Call {
    val scope = rememberCoroutineScope()
    return remember { Call(scope) }
}

/**
 * A screen of the account steps: a title and a line of detail, then [body],
 * with [actions] pinned at the bottom. [centered] puts the block in the middle
 * of the free space, as the question screens do; the rest start under the bar.
 * Scrolls when a translation or a large font does not fit.
 */
@Composable
internal fun ColumnScope.Page(
    title: String,
    detail: AnnotatedString? = null,
    centered: Boolean = false,
    top: @Composable () -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier.heightIn(min = maxHeight).verticalScroll(rememberScrollState()).then(if (centered) Modifier else Modifier.padding(top = Space.xl)),
            verticalArrangement = Arrangement.spacedBy(Space.xl, if (centered) Alignment.CenterVertically else Alignment.Top),
        ) {
            top()
            Text(title, style = Theme.type.screenTitle, color = Theme.colors.ink)
            detail?.let { Text(it, style = Theme.type.lead, color = Theme.colors.soft) }
            body()
        }
    }
    Column(Modifier.padding(top = Space.xl, bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m), content = actions)
}

private fun plain(text: String) = AnnotatedString(text)

/** A label above a box, with a line of hint (or the error) below it. */
@Composable
private fun LabelledField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    hint: String? = null, error: String? = null, keyboard: KeyboardOptions = KeyboardOptions.Default,
    transformation: VisualTransformation = VisualTransformation.None, onDone: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = Theme.type.label, color = if (error != null) Theme.colors.destructive else Theme.colors.accent,
            modifier = Modifier.padding(start = Space.xs))
        OutlinedTextField(
            value = value, onValueChange = onChange, singleLine = true, isError = error != null,
            textStyle = Theme.type.input, shape = MaterialTheme.shapes.small, visualTransformation = transformation,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Theme.colors.accent, unfocusedBorderColor = Theme.colors.inputBorder,
                errorBorderColor = Theme.colors.destructive,
                focusedContainerColor = Theme.colors.card, unfocusedContainerColor = Theme.colors.card, errorContainerColor = Theme.colors.card,
                focusedTextColor = Theme.colors.ink, unfocusedTextColor = Theme.colors.ink, errorTextColor = Theme.colors.ink,
                cursorColor = Theme.colors.accent),
            keyboardOptions = keyboard.copy(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = Size.button).semantics { contentDescription = label },
        )
        val line = error ?: hint
        line?.let {
            Text(it, style = Theme.type.footnote, color = if (error != null) Theme.colors.destructive else Theme.colors.muted,
                modifier = Modifier.padding(horizontal = Space.xs))
        }
    }
}

/** Shows a code as groups of four without changing what is stored. */
private object Grouped : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = raw.chunked(Crockford.GROUP).joinToString(" ")
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset <= 0) 0 else offset + (offset - 1) / Crockford.GROUP
            override fun transformedToOriginal(offset: Int) = (offset - offset / (Crockford.GROUP + 1)).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(shown), map)
    }
}

private val codeKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false)

@Composable
private fun CallError(call: Call) {
    if (call.failed) Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive)
}

/** The screen that asks whether the account exists at all. */
@Composable
internal fun ColumnScope.WhereStep(flow: OnboardingFlow, model: AppModel) {
    val link by model.inviteCode.collectAsState()
    Page(
        stringResource(R.string.where_title), plain(stringResource(R.string.where_detail)), centered = true,
        actions = {
            FilledAction(stringResource(R.string.where_online)) {
                flow.signingIn = false
                link?.let { flow.inviteCode = it }
                // A link opened earlier already brought the invitation.
                flow.go(if (link != null) Step.Consent else Step.Invite)
            }
            OutlinedAction(stringResource(R.string.where_local), border = Theme.colors.buttonOutline, borderWidth = Size.hairline,
                container = androidx.compose.ui.graphics.Color.Transparent) { flow.finish(model) }
        },
    ) {
        Column(Modifier.fillMaxWidth().background(Theme.colors.card, MaterialTheme.shapes.medium).padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m - Space.xxs)) {
            listOf(R.string.where_friends, R.string.where_sync, R.string.where_later).forEach {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Theme.colors.accent, modifier = Modifier.size(Size.checkbox))
                    Text(stringResource(it), style = Theme.type.subtitle.copy(lineHeight = Theme.type.footnote.lineHeight), color = Theme.colors.soft)
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.InviteStep(flow: OnboardingFlow, accounts: AccountService, finishLocal: () -> Unit) {
    val call = rememberCall()
    var unknown by remember { mutableStateOf(false) }
    Page(
        stringResource(R.string.invite_title), plain(stringResource(R.string.invite_detail)),
        actions = {
            CallError(call)
            Text(stringResource(R.string.invite_none), style = Theme.type.secondary, color = Theme.colors.muted)
            FilledAction(stringResource(R.string.continue_), enabled = flow.inviteCode.length == Crockford.INVITE_LENGTH && !call.busy) {
                call.run {
                    when (accounts.checkInvite(flow.inviteCode)) {
                        InviteCheck.Valid -> flow.go(Step.Consent)
                        InviteCheck.Unknown -> unknown = true
                    }
                }
            }
            TextAction(stringResource(R.string.invite_local), onClick = finishLocal)
        },
    ) {
        LabelledField(stringResource(R.string.invite_label), flow.inviteCode,
            { flow.inviteCode = Crockford.normalise(it, Crockford.INVITE_LENGTH); unknown = false },
            hint = stringResource(R.string.invite_hint), error = if (unknown) stringResource(R.string.invite_unknown) else null,
            keyboard = codeKeyboard, transformation = Grouped)
    }
}

@Composable
internal fun ColumnScope.ConsentStep(flow: OnboardingFlow) {
    val uri = LocalUriHandler.current
    Page(
        stringResource(R.string.consent_title), plain(stringResource(R.string.consent_detail)), centered = true,
        actions = { FilledAction(stringResource(R.string.continue_), enabled = flow.consented) { flow.go(Step.Email) } },
    ) {
        Row(
            Modifier.fillMaxWidth().background(Theme.colors.card, MaterialTheme.shapes.medium)
                .toggleable(value = flow.consented, role = Role.Checkbox) { flow.consented = it }.padding(Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.m + Space.xxs), verticalAlignment = Alignment.Top,
        ) {
            Checkbox(flow.consented, onCheckedChange = null, modifier = Modifier.size(Size.checkbox),
                colors = CheckboxDefaults.colors(checkedColor = Theme.colors.accent, checkmarkColor = Theme.colors.onAccent,
                    uncheckedColor = Theme.colors.inputBorder))
            Text(stringResource(R.string.consent_check), style = Theme.type.subtitle.copy(lineHeight = Theme.type.lead.lineHeight), color = Theme.colors.ink)
        }
        val url = stringResource(R.string.privacy_url)
        Text(stringResource(R.string.privacy_policy), style = Theme.type.subtitle.copy(fontWeight = FontWeight.Bold), color = Theme.colors.accent,
            modifier = Modifier.heightIn(min = Size.minTap).clickable(role = Role.Button) { uri.openUri(url) }
                .padding(horizontal = Space.xs))
    }
}

@Composable
internal fun ColumnScope.EmailStep(flow: OnboardingFlow, accounts: AccountService) {
    val call = rememberCall()
    val valid = flow.email.trim().matches(EMAIL)
    val send = {
        call.run {
            accounts.requestMagicLink(flow.email.trim())
            flow.go(Step.CheckEmail)
        }
    }
    Page(
        stringResource(R.string.email_title), plain(stringResource(R.string.email_detail)),
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.continue_), enabled = valid && !call.busy) { send() }
            if (!flow.signingIn) TextAction(stringResource(R.string.email_skip)) { flow.email = ""; flow.go(Step.Username) }
        },
    ) {
        LabelledField(stringResource(R.string.email_label), flow.email, { flow.email = it },
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            onDone = if (valid) ({ send() }) else null)
    }
}

private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

@Composable
internal fun ColumnScope.CheckEmailStep(flow: OnboardingFlow, model: AppModel, finishSignedIn: () -> Unit) {
    val accounts = model.accounts
    val call = rememberCall()
    var code by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    var again by remember { mutableStateOf(false) }
    // A sign-in link opened on this phone brings its code.
    val link by model.signInCode.collectAsState()
    LaunchedEffect(link) {
        link?.let { code = it; model.usedSignInCode() }
    }
    val submit = {
        call.run {
            when (accounts.redeemCode(flow.email.trim(), code)) {
                RedeemResult.Ok -> if (flow.signingIn) finishSignedIn() else flow.go(Step.Name)
                RedeemResult.Wrong -> problem = R.string.code_wrong
                RedeemResult.Expired -> problem = R.string.code_expired
            }
        }
    }
    Page(
        stringResource(R.string.check_title), plain(stringResource(R.string.check_detail, flow.email.trim())),
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.continue_), enabled = code.length == Crockford.SIGN_IN_LENGTH && !call.busy) { submit() }
            TextAction(if (again) stringResource(R.string.sent_again) else stringResource(R.string.send_again)) {
                if (!call.busy && !again) call.run { accounts.requestMagicLink(flow.email.trim()); again = true }
            }
        },
    ) {
        LabelledField(stringResource(R.string.code_label), code, { code = Crockford.normalise(it, Crockford.SIGN_IN_LENGTH); problem = null },
            hint = stringResource(R.string.code_hint), error = problem?.let { stringResource(it) },
            keyboard = codeKeyboard, transformation = Grouped, onDone = if (code.length == Crockford.SIGN_IN_LENGTH) submit else null)
    }
}

@Composable
internal fun ColumnScope.UsernameStep(flow: OnboardingFlow) {
    Page(
        stringResource(R.string.username_title), plain(stringResource(R.string.username_detail)),
        actions = {
            FilledAction(stringResource(R.string.continue_), enabled = flow.username.isNotBlank()) { flow.go(Step.Name) }
            TextAction(stringResource(R.string.skip)) { flow.username = ""; flow.go(Step.Name) }
        },
    ) {
        LabelledField(stringResource(R.string.username_label), flow.username,
            { flow.username = it.filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '_' }.take(USERNAME_MAX) },
            hint = stringResource(R.string.username_hint),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
    }
}

private const val USERNAME_MAX = 30

@Composable
internal fun ColumnScope.NameStep(flow: OnboardingFlow) {
    Page(
        stringResource(R.string.name_title), plain(stringResource(R.string.name_detail)),
        actions = { FilledAction(stringResource(R.string.continue_), enabled = flow.displayName.isNotBlank()) { flow.go(Step.Gender) } },
    ) {
        LabelledField(stringResource(R.string.name_label), flow.displayName, { flow.displayName = it },
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            onDone = if (flow.displayName.isNotBlank()) ({ flow.go(Step.Gender) }) else null)
    }
}

@Composable
internal fun ColumnScope.GenderStep(flow: OnboardingFlow, accounts: AccountService) {
    val call = rememberCall()
    val pick = { g: Gender? ->
        flow.gender = g
        call.run {
            accounts.setProfile(flow.displayName.trim(), flow.username.ifBlank { null }, g)
            flow.go(Step.Passkey)
        }
    }
    Page(
        stringResource(R.string.gender_title), AnnotatedString.fromHtml(stringResource(R.string.gender_detail)),
        actions = {
            CallError(call)
            listOf(Gender.Male to R.string.gender_male, Gender.Female to R.string.gender_female, Gender.NonBinary to R.string.gender_nonbinary)
                .forEach { (g, label) ->
                    OutlinedAction(stringResource(label), border = Theme.colors.buttonOutline, borderWidth = Size.hairline,
                        container = androidx.compose.ui.graphics.Color.Transparent) { if (!call.busy) pick(g) }
                }
            TextAction(stringResource(R.string.skip)) { if (!call.busy) pick(null) }
        },
    )
}

@Composable
internal fun ColumnScope.PasskeyStep(flow: OnboardingFlow, accounts: AccountService) {
    val call = rememberCall()
    Page(
        stringResource(R.string.passkey_title), plain(stringResource(R.string.passkey_detail)), centered = true,
        top = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.size(Size.badge).clip(CircleShape).background(Theme.colors.streakCard),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(painterResource(R.drawable.ic_passkey), contentDescription = null, tint = Theme.colors.accent, modifier = Modifier.size(Size.badgeIcon))
                }
            }
        },
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.passkey_save), enabled = !call.busy) {
                call.run { if (accounts.createPasskey() == Passkey.Saved) flow.go(Step.Recovery) }
            }
            // With an email there is a way back in without it; with only a username the passkey is the account.
            if (flow.email.isNotBlank()) TextAction(stringResource(R.string.passkey_later)) { flow.go(Step.Recovery) }
        },
    )
}

@Composable
internal fun ColumnScope.RecoveryStep(flow: OnboardingFlow, accounts: AccountService, finish: () -> Unit) {
    val call = rememberCall()
    LaunchedEffect(Unit) {
        if (flow.recoveryCode == null) flow.recoveryCode = try { accounts.createRecoveryCode() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
    val code = flow.recoveryCode
    Page(
        stringResource(R.string.recovery_title), plain(stringResource(R.string.recovery_detail)),
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.recovery_done), enabled = code != null, onClick = finish)
            TextAction(stringResource(R.string.recovery_gpm)) {
                if (code != null) call.run { if (accounts.saveRecoveryCode(code)) finish() }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().background(Theme.colors.card, MaterialTheme.shapes.medium).padding(horizontal = Space.l, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m + Space.xxs)) {
            Crockford.grouped(code.orEmpty()).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    repeat(3) { i ->
                        Text(row.getOrNull(i).orEmpty(), Modifier.weight(1f), style = Theme.type.code, color = Theme.colors.ink, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Text(stringResource(R.string.recovery_note), style = Theme.type.secondary, color = Theme.colors.muted)
    }
}

@Composable
internal fun ColumnScope.SignInStep(flow: OnboardingFlow, accounts: AccountService, finish: () -> Unit) {
    val call = rememberCall()
    Page(
        stringResource(R.string.signin_title), plain(stringResource(R.string.signin_detail)), centered = true,
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.signin_passkey), enabled = !call.busy) {
                call.run { if (accounts.signInWithPasskey()) finish() }
            }
            OutlinedAction(stringResource(R.string.signin_email), border = Theme.colors.buttonOutline, borderWidth = Size.hairline,
                container = androidx.compose.ui.graphics.Color.Transparent) { flow.go(Step.Email) }
        },
    )
}
