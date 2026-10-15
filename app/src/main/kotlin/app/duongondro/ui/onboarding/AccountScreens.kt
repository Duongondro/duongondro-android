package app.duongondro.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import app.duongondro.account.AccountManager
import app.duongondro.account.AccountStatus
import app.duongondro.account.Crockford
import app.duongondro.account.EmailAdd
import app.duongondro.account.EmailVerify
import app.duongondro.account.Gender
import app.duongondro.account.InviteCheck
import app.duongondro.account.LinkRequest
import app.duongondro.account.PasskeyResult
import app.duongondro.account.Redeem
import app.duongondro.core.sync.Account
import app.duongondro.core.sync.RecoveryCode
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
    if (call.failed) ErrorLine(stringResource(R.string.account_error))
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = Theme.type.footnote, color = Theme.colors.destructive)
}

/** After any sign-in or sign-up: a new account gets its keys and then its optional profile; an existing one sets up, restores, or is done. */
private suspend fun route(flow: OnboardingFlow, accounts: AccountManager, created: Boolean, finish: () -> Unit) {
    if (created) {
        accountMade(flow, accounts)
        return
    }
    when (accounts.standing()) {
        Account.Standing.READY -> finish()
        Account.Standing.SET_UP -> flow.go(Step.Recovery)
        Account.Standing.RESTORE -> flow.go(Step.Restore)
    }
}

/**
 * The account exists: its keys are made at once, silently (design 07), so the
 * friendship link's proof is redeemed now and the recovery code waits on its
 * own step. A failure here only means the Recovery step makes them instead.
 * The steps before the account leave the back stack.
 */
private suspend fun accountMade(flow: OnboardingFlow, accounts: AccountManager) {
    flow.signingIn = false
    flow.onAccount?.invoke()
    try {
        accounts.setUpKeys()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The Recovery step tries again and says so.
    }
    flow.afterAccount(Step.Name)
}

/** A sign-up step reached again once the account exists (Back, or Settings): straight on to the name. */
@Composable
private fun SkipOnceAccountExists(flow: OnboardingFlow, accounts: AccountManager) {
    LaunchedEffect(Unit) {
        if (!flow.signingIn && accounts.state.value.status.let { it == AccountStatus.NEEDS_KEYS || it == AccountStatus.READY }) {
            flow.afterAccount(Step.Name)
        }
    }
}

/**
 * Checks the friendship link opened before sign-up, if it is not the one
 * checked last: a valid one goes with the sign-up as proof, any other is
 * dropped. False when it could not be checked (offline, the server busy).
 */
private suspend fun checkLink(flow: OnboardingFlow, model: AppModel, accounts: AccountManager): Boolean {
    val opened = model.inviteCode.value
    if (opened == flow.checkedLink) return true
    if (opened == null) {
        accounts.clearInvitation()
        flow.checkedLink = null
        return true
    }
    val valid = try {
        accounts.checkInvite(opened) == InviteCheck.Valid
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return false
    }
    if (!valid) accounts.clearInvitation()
    flow.checkedLink = opened
    return true
}

/** "Continue without the link": the sign-up goes on without its proof; the link can still be accepted afterwards. */
private fun skipLink(flow: OnboardingFlow, model: AppModel, accounts: AccountManager) {
    accounts.clearInvitation()
    flow.checkedLink = model.inviteCode.value
}

/** A redemption's answer: signed in, or the reason it was not, shown under the field. */
private suspend fun redeemed(result: Redeem, flow: OnboardingFlow, accounts: AccountManager, finish: () -> Unit): Int? = when (result) {
    is Redeem.SignedIn -> { route(flow, accounts, result.created, finish); null }
    Redeem.Wrong -> R.string.code_wrong
    Redeem.NoAccount -> R.string.no_account
    Redeem.InviteGone -> { accounts.clearInvitation(); R.string.signup_link_gone }
    Redeem.TooMany -> R.string.try_later
    Redeem.InvitationNeeded -> R.string.invite_needed
    Redeem.OtherAccountData -> { flow.otherAccount = true; null }
}

/**
 * "This phone holds practice from another account": erase it here and go on
 * with the sign-in, or cancel, which ends the new session with nothing uploaded.
 */
@Composable
private fun OtherAccountDialog(
    flow: OnboardingFlow, accounts: AccountManager, finish: () -> Unit,
    onContinue: suspend (created: Boolean) -> Unit = { created -> route(flow, accounts, created, finish) },
) {
    // The step's scope, not the dialog's: closing the dialog must not cancel the erase.
    val scope = rememberCoroutineScope()
    if (!flow.otherAccount) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.other_account_title)) },
        text = { Text(stringResource(R.string.other_account_detail)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                flow.otherAccount = false
                scope.launch {
                    runCatching { accounts.eraseOtherAccountAndContinue() }.onSuccess { created -> onContinue(created) }
                }
            }) { Text(stringResource(R.string.erase_and_continue), color = Theme.colors.destructive) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                flow.otherAccount = false
                scope.launch { accounts.abandonHeldSignIn() }
            }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The screen that asks whether the account exists at all. */
@Composable
internal fun ColumnScope.WhereStep(flow: OnboardingFlow, model: AppModel, finishLocal: () -> Unit) {
    val accounts = model.accounts
    val status = accounts?.state?.collectAsState()?.value?.status
    val made = !flow.signingIn && (status == AccountStatus.NEEDS_KEYS || status == AccountStatus.READY)
    Page(
        stringResource(R.string.where_title), plain(stringResource(R.string.where_detail)), centered = true,
        actions = {
            FilledAction(stringResource(R.string.where_online), enabled = accounts != null) {
                flow.signingIn = false
                if (made) flow.afterAccount(Step.Name) else flow.go(Step.Consent)
            }
            // An account that exists is finished through name, gender and recovery code, never left half made.
            if (!made) OutlinedAction(stringResource(R.string.where_local), border = Theme.colors.buttonOutline, borderWidth = Size.hairline,
                container = androidx.compose.ui.graphics.Color.Transparent) { accounts?.clearInvitation(); finishLocal() }
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

/** Consent first (GDPR Art. 9): nothing is created before it. */
@Composable
internal fun ColumnScope.ConsentStep(flow: OnboardingFlow, accounts: AccountManager) {
    val uri = LocalUriHandler.current
    SkipOnceAccountExists(flow, accounts)
    Page(
        stringResource(R.string.consent_title), plain(stringResource(R.string.consent_detail)), centered = true,
        actions = { FilledAction(stringResource(R.string.continue_), enabled = flow.consented) { flow.go(Step.Passkey) } },
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
internal fun ColumnScope.EmailStep(flow: OnboardingFlow, model: AppModel, accounts: AccountManager) {
    val call = rememberCall()
    var problem by remember { mutableStateOf<Int?>(null) }
    var linkFailed by remember { mutableStateOf(false) }
    SkipOnceAccountExists(flow, accounts)
    val valid = flow.email.trim().matches(EMAIL)
    val send = { withoutLink: Boolean ->
        problem = null
        call.run {
            if (withoutLink) skipLink(flow, model, accounts)
            if (!flow.signingIn && !checkLink(flow, model, accounts)) {
                linkFailed = true
                problem = R.string.link_check_failed
                return@run
            }
            linkFailed = false
            when (accounts.requestMagicLink(flow.email.trim(), signUp = !flow.signingIn, consent = flow.consented)) {
                LinkRequest.Sent -> flow.go(Step.CheckEmail)
                LinkRequest.UnknownInvite -> { accounts.clearInvitation(); problem = R.string.signup_link_gone }
                LinkRequest.TooMany -> problem = R.string.try_later
                LinkRequest.InvitationNeeded -> problem = R.string.invite_needed
                LinkRequest.TooManySignUps -> problem = R.string.signup_too_many
            }
        }
    }
    Page(
        stringResource(R.string.email_title), plain(stringResource(R.string.email_detail)),
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.continue_), enabled = valid && !call.busy) { send(false) }
            if (linkFailed) TextAction(stringResource(R.string.link_skip)) { if (valid && !call.busy) send(true) }
        },
    ) {
        LabelledField(stringResource(R.string.email_label), flow.email, { flow.email = it; problem = null },
            error = problem?.let { stringResource(it) },
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            onDone = if (valid) ({ send(false) }) else null)
    }
}

private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

@Composable
internal fun ColumnScope.CheckEmailStep(flow: OnboardingFlow, model: AppModel, accounts: AccountManager, finish: () -> Unit) {
    val call = rememberCall()
    var code by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    var again by remember { mutableStateOf(false) }
    // A magic link opened on this phone signs in by itself, but only here, and only
    // when this phone asked for one in the last 15 minutes (AccountManager.expectsLink).
    val link by model.magicLink.collectAsState()
    LaunchedEffect(link) {
        val token = link ?: return@LaunchedEffect
        model.usedMagicLink()
        call.run { accounts.redeemLink(token)?.let { problem = redeemed(it, flow, accounts, finish) } }
    }
    // A link that arrives after leaving this step is never redeemed.
    DisposableEffect(Unit) { onDispose { model.usedMagicLink() } }
    val submit = {
        call.run { problem = redeemed(accounts.redeemCode(flow.email.trim(), code), flow, accounts, finish) }
    }
    OtherAccountDialog(flow, accounts, finish)
    SkipOnceAccountExists(flow, accounts)
    Page(
        stringResource(R.string.check_title), plain(stringResource(R.string.check_detail, flow.email.trim())),
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.continue_), enabled = code.length == Crockford.SIGN_IN_LENGTH && !call.busy) { submit() }
            TextAction(if (again) stringResource(R.string.sent_again) else stringResource(R.string.send_again)) {
                if (!call.busy && !again) call.run {
                    when (accounts.requestMagicLink(flow.email.trim(), signUp = !flow.signingIn, consent = flow.consented)) {
                        LinkRequest.Sent -> again = true
                        LinkRequest.UnknownInvite -> { accounts.clearInvitation(); problem = R.string.signup_link_gone }
                        LinkRequest.TooMany -> problem = R.string.try_later
                        LinkRequest.InvitationNeeded -> problem = R.string.invite_needed
                        LinkRequest.TooManySignUps -> problem = R.string.signup_too_many
                    }
                }
            }
        },
    ) {
        LabelledField(stringResource(R.string.code_label), code, { code = Crockford.normalise(it, Crockford.SIGN_IN_LENGTH); problem = null },
            hint = stringResource(R.string.code_hint), error = problem?.let { stringResource(it) },
            keyboard = codeKeyboard, transformation = Grouped, onDone = if (code.length == Crockford.SIGN_IN_LENGTH) submit else null)
    }
}

/**
 * Optional, after the account exists: the name the server gave it (a random
 * one, or none) is filled in; Continue keeps what is in the box, cleared or
 * not (no name shows as "A friend"), Skip keeps the server's.
 */
@Composable
internal fun ColumnScope.NameStep(flow: OnboardingFlow, accounts: AccountManager) {
    val call = rememberCall()
    val load = {
        call.run {
            val name = accounts.serverDisplayName()
            flow.serverName = name
            flow.displayName = name
        }
    }
    LaunchedEffect(Unit) { if (flow.serverName == null) load() }
    val loaded = flow.serverName != null
    val next = { flow.go(Step.Gender) }
    Page(
        stringResource(R.string.name_title),
        plain(stringResource(if (flow.serverName.isNullOrEmpty()) R.string.name_detail else R.string.name_random_detail)),
        actions = {
            CallError(call)
            if (call.failed) FilledAction(stringResource(R.string.try_again), enabled = !call.busy) { load() }
            else FilledAction(stringResource(R.string.continue_), enabled = loaded, onClick = next)
            TextAction(stringResource(R.string.skip)) {
                if (call.busy) return@TextAction
                flow.displayName = flow.serverName.orEmpty()
                next()
            }
        },
    ) {
        LabelledField(stringResource(R.string.name_label), flow.displayName, { if (loaded) flow.displayName = it.take(64) },
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            onDone = if (loaded) next else null)
    }
}

/** Optional; then the name and gender are saved, only what changed, and an e-mail is offered unless the account has one. */
@Composable
internal fun ColumnScope.GenderStep(flow: OnboardingFlow, accounts: AccountManager) {
    val call = rememberCall()
    val pick = { g: Gender? ->
        flow.gender = g
        call.run {
            val name = flow.displayName.trim().takeIf { it != flow.serverName.orEmpty() }
            accounts.setProfile(name, g)
            if (name != null) flow.serverName = name
            // An account made by e-mail has its address already.
            flow.go(if (accounts.state.value.email != null) Step.Recovery else Step.AddEmail)
        }
    }
    Page(
        stringResource(R.string.gender_title), plain(stringResource(R.string.gender_detail)),
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

/** Optional: an e-mail as a second way to sign in (a new phone, a lost passkey), confirmed with a mailed code. */
@Composable
internal fun ColumnScope.AddEmailStep(flow: OnboardingFlow, accounts: AccountManager) {
    val call = rememberCall()
    var problem by remember { mutableStateOf<Int?>(null) }
    val valid = flow.addedEmail.trim().matches(EMAIL)
    val send = {
        problem = null
        call.run {
            when (accounts.requestEmail(flow.addedEmail.trim())) {
                EmailAdd.Sent -> flow.go(Step.CheckAddedEmail)
                EmailAdd.NotAnAddress -> problem = R.string.account_error
                EmailAdd.TooMany -> problem = R.string.try_later
            }
        }
    }
    Page(
        stringResource(R.string.add_email_title), plain(stringResource(R.string.add_email_detail)),
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.continue_), enabled = valid && !call.busy) { send() }
            TextAction(stringResource(R.string.skip)) { if (!call.busy) flow.go(Step.Recovery) }
        },
    ) {
        LabelledField(stringResource(R.string.email_label), flow.addedEmail, { flow.addedEmail = it; problem = null },
            error = problem?.let { stringResource(it) },
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            onDone = if (valid) send else null)
    }
}

/** The code mailed to the address being added; Back changes the address. */
@Composable
internal fun ColumnScope.CheckAddedEmailStep(flow: OnboardingFlow, accounts: AccountManager) {
    val call = rememberCall()
    var code by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    var again by remember { mutableStateOf(false) }
    val submit = {
        problem = null
        call.run {
            when (accounts.verifyEmail(flow.addedEmail, code)) {
                EmailVerify.Added -> flow.go(Step.Recovery)
                EmailVerify.Wrong -> problem = R.string.code_wrong
                EmailVerify.Taken -> problem = R.string.email_taken
                EmailVerify.TooMany -> problem = R.string.try_later
            }
        }
    }
    Page(
        stringResource(R.string.check_title), plain(stringResource(R.string.add_email_sent, flow.addedEmail.trim())),
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.continue_), enabled = code.length == Crockford.SIGN_IN_LENGTH && !call.busy) { submit() }
            TextAction(if (again) stringResource(R.string.sent_again) else stringResource(R.string.send_again)) {
                if (!call.busy && !again) call.run {
                    when (accounts.requestEmail(flow.addedEmail.trim())) {
                        EmailAdd.Sent -> again = true
                        EmailAdd.NotAnAddress -> problem = R.string.account_error
                        EmailAdd.TooMany -> problem = R.string.try_later
                    }
                }
            }
            TextAction(stringResource(R.string.skip)) { if (!call.busy) flow.go(Step.Recovery) }
        },
    ) {
        LabelledField(stringResource(R.string.code_label), code, { code = Crockford.normalise(it, Crockford.SIGN_IN_LENGTH); problem = null },
            hint = stringResource(R.string.code_hint), error = problem?.let { stringResource(it) },
            keyboard = codeKeyboard, transformation = Grouped, onDone = if (code.length == Crockford.SIGN_IN_LENGTH) submit else null)
    }
}

/**
 * Sign-up's first step after consent: the passkey makes the account, with no
 * profile at all. "Use email instead" is for a phone or password manager that
 * cannot make one: the emailed link or code makes the account instead.
 */
@Composable
internal fun ColumnScope.PasskeyStep(flow: OnboardingFlow, model: AppModel, accounts: AccountManager) {
    val call = rememberCall()
    val activity = LocalContext.current
    var problem by remember { mutableStateOf<Int?>(null) }
    var linkFailed by remember { mutableStateOf(false) }
    SkipOnceAccountExists(flow, accounts)
    OtherAccountDialog(flow, accounts, finish = {}) { accountMade(flow, accounts) }
    val save = { withoutLink: Boolean ->
        problem = null
        call.run {
            if (withoutLink) skipLink(flow, model, accounts)
            if (!checkLink(flow, model, accounts)) {
                linkFailed = true
                problem = R.string.link_check_failed
                return@run
            }
            linkFailed = false
            when (accounts.createPasskey(activity, profile = null, consent = flow.consented)) {
                PasskeyResult.Saved -> accountMade(flow, accounts)
                PasskeyResult.Cancelled -> Unit
                PasskeyResult.Unavailable -> problem = R.string.passkey_unavailable
                // No username is sent, so the server has none to refuse.
                PasskeyResult.UsernameTaken -> problem = R.string.account_error
                PasskeyResult.InviteGone -> { accounts.clearInvitation(); problem = R.string.signup_link_gone }
                PasskeyResult.InvitationNeeded -> problem = R.string.invite_needed
                PasskeyResult.TooManySignUps -> problem = R.string.signup_too_many
                PasskeyResult.TooMany -> problem = R.string.try_later
                PasskeyResult.OtherAccountData -> flow.otherAccount = true
            }
        }
    }
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
            problem?.let { ErrorLine(stringResource(it)) }
            FilledAction(stringResource(R.string.passkey_save), enabled = !call.busy) { save(false) }
            if (linkFailed) TextAction(stringResource(R.string.link_skip)) { if (!call.busy) save(true) }
            TextAction(stringResource(R.string.passkey_use_email)) { if (!call.busy) flow.go(Step.Email) }
        },
    )
}

/** Keys are made here, silently, and the recovery code shown once (resumed with the same code after an interruption). */
@Composable
internal fun ColumnScope.RecoveryStep(flow: OnboardingFlow, accounts: AccountManager, finish: () -> Unit) {
    val call = rememberCall()
    val setUp = rememberCall()
    val activity = LocalContext.current
    // The work runs in AccountManager's scope under its lock; a rotation only re-asks for the same code.
    // setUpKeys returns the code already on show when it is this account's, and
    // otherwise sets up this account's own keys.
    val makeKeys = { setUp.run { accounts.setUpKeys() } }
    LaunchedEffect(Unit) { makeKeys() }
    val code by accounts.shownCode.collectAsState()
    Page(
        stringResource(R.string.recovery_title), plain(stringResource(R.string.recovery_detail)),
        actions = {
            CallError(call)
            CallError(setUp)
            if (setUp.failed) {
                FilledAction(stringResource(R.string.try_again), enabled = !setUp.busy) { makeKeys() }
            } else {
                FilledAction(stringResource(R.string.recovery_done), enabled = code != null) { flow.go(Step.RecoveryCheck) }
            }
            TextAction(stringResource(R.string.recovery_gpm)) {
                val shown = code
                if (shown != null && !call.busy) call.run { if (accounts.saveRecoveryCode(activity, shown)) finish() }
            }
        },
    ) {
        RecoveryGrid(code)
        Text(stringResource(R.string.recovery_note), style = Theme.type.secondary, color = Theme.colors.muted)
    }
}

/** The code in groups of four, three to a row. */
@Composable
internal fun RecoveryGrid(code: String?) {
    Column(Modifier.fillMaxWidth().background(Theme.colors.card, MaterialTheme.shapes.medium).padding(horizontal = Space.l, vertical = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.m + Space.xxs)) {
        val groups = RecoveryCode.normalise(code.orEmpty()).chunked(Crockford.GROUP)
        groups.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                repeat(3) { i ->
                    Text(row.getOrNull(i).orEmpty(), Modifier.weight(1f), style = Theme.type.code, color = Theme.colors.ink, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Two of the code's groups typed back, as the code screen promised (iOS's RecoveryCheckView). */
@Composable
internal fun ColumnScope.RecoveryCheckStep(code: String, back: () -> Unit, done: () -> Unit) {
    val groups = remember(code) { RecoveryCode.normalise(code).chunked(Crockford.GROUP) }
    val asked = remember(code) { groups.indices.shuffled().take(2).sorted() }
    val answers = remember(code) { androidx.compose.runtime.mutableStateListOf("", "") }
    var wrong by remember { mutableStateOf(false) }
    val check = {
        if (asked.indices.all { n -> RecoveryCode.normalise(answers[n]) == groups[asked[n]] }) done() else wrong = true
    }
    Page(
        stringResource(R.string.recovery_check_title), plain(stringResource(R.string.recovery_check_detail)),
        actions = {
            FilledAction(stringResource(R.string.done), enabled = answers.all { it.isNotBlank() }) { check() }
            TextAction(stringResource(R.string.recovery_show_again), onClick = back)
        },
    ) {
        asked.forEachIndexed { n, i ->
            LabelledField(stringResource(R.string.recovery_group_n, i + 1), answers[n],
                { answers[n] = Crockford.normalise(it, groups[i].length); wrong = false },
                error = if (wrong) "" else null, keyboard = codeKeyboard,
                onDone = if (n == asked.lastIndex && answers.all { it.isNotBlank() }) check else null)
        }
        if (wrong) ErrorLine(stringResource(R.string.recovery_check_wrong))
    }
}

/** A new phone, without the old one: the recovery code brings the keys back. */
@Composable
internal fun ColumnScope.RestoreStep(accounts: AccountManager, finish: () -> Unit) {
    val call = rememberCall()
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val submit = { call.run { if (accounts.restore(code)) finish() else wrong = true } }
    Page(
        stringResource(R.string.restore_title), plain(stringResource(R.string.restore_detail)),
        actions = {
            CallError(call)
            FilledAction(stringResource(R.string.continue_), enabled = code.length == Crockford.RECOVERY_LENGTH && !call.busy) { submit() }
        },
    ) {
        LabelledField(stringResource(R.string.restore_label), code, { code = Crockford.normalise(it, Crockford.RECOVERY_LENGTH); wrong = false },
            error = if (wrong) stringResource(R.string.restore_wrong) else null, keyboard = codeKeyboard, transformation = Grouped,
            onDone = if (code.length == Crockford.RECOVERY_LENGTH) submit else null)
    }
}

@Composable
internal fun ColumnScope.SignInStep(flow: OnboardingFlow, accounts: AccountManager, finish: () -> Unit) {
    val call = rememberCall()
    val activity = LocalContext.current
    var problem by remember { mutableStateOf<Int?>(null) }
    OtherAccountDialog(flow, accounts, finish)
    Page(
        stringResource(R.string.signin_title), plain(stringResource(R.string.signin_detail)), centered = true,
        actions = {
            CallError(call)
            problem?.let { ErrorLine(stringResource(it)) }
            FilledAction(stringResource(R.string.signin_passkey), enabled = !call.busy) {
                problem = null
                call.run {
                    val result = accounts.signInWithPasskey(activity)
                    problem = if (result == null) R.string.signin_no_passkey else redeemed(result, flow, accounts, finish)
                }
            }
            OutlinedAction(stringResource(R.string.signin_email), border = Theme.colors.buttonOutline, borderWidth = Size.hairline,
                container = androidx.compose.ui.graphics.Color.Transparent) { if (!call.busy) flow.go(Step.Email) }
        },
    )
}

/** Survives rotation, so turning the phone never makes a second code; never saved to the instance state. */
class RecoveryCodeModel : androidx.lifecycle.ViewModel() {
    var checking by mutableStateOf(false)
    /** An unfinished account whose keys live on another phone: the recovery code restores them here instead. */
    var restoring by mutableStateOf(false)
}

/**
 * From Settings: a new recovery code (the old one stops working), or the
 * first one when a set-up was interrupted; shown once, then two groups typed
 * back, as during sign-up.
 */
@Composable
fun RecoveryCodeScreen(accounts: AccountManager, back: () -> Unit) {
    val m: RecoveryCodeModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val call = rememberCall()
    val save = rememberCall()
    val activity = LocalContext.current
    // AccountManager keeps the code (shownCode) and runs the work under its lock, so a
    // rotation shows the same code instead of making a second one.
    val make = {
        call.run {
            if (accounts.state.value.status != app.duongondro.account.AccountStatus.NEEDS_KEYS) accounts.newRecoveryCode()
            else when (accounts.standing()) {
                Account.Standing.RESTORE -> m.restoring = true
                Account.Standing.SET_UP -> accounts.setUpKeys()
                Account.Standing.READY -> back()
            }
        }
    }
    LaunchedEffect(Unit) { make() }
    Column(Modifier.fillMaxSize().background(Theme.colors.ground).safeDrawingPadding().padding(horizontal = Space.xl)) {
        Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, modifier = Modifier.size(Size.minTap)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Theme.colors.ink)
            }
        }
        val code by accounts.shownCode.collectAsState()
        if (m.restoring) {
            RestoreStep(accounts) { accounts.scheduleSync(0); back() }
        } else if (m.checking && code != null) {
            RecoveryCheckStep(code!!, back = { m.checking = false }) { accounts.confirmRecoveryCode(); back() }
        } else {
            Page(
                stringResource(R.string.recovery_title), plain(stringResource(R.string.recovery_detail)),
                actions = {
                    CallError(call)
                    CallError(save)
                    if (call.failed) FilledAction(stringResource(R.string.try_again), enabled = !call.busy) { make() }
                    else FilledAction(stringResource(R.string.recovery_done), enabled = code != null) { m.checking = true }
                    TextAction(stringResource(R.string.recovery_gpm)) {
                        code?.let { c -> save.run { if (accounts.saveRecoveryCode(activity, c)) back() } }
                    }
                },
            ) {
                RecoveryGrid(code)
                Text(stringResource(R.string.recovery_note), style = Theme.type.secondary, color = Theme.colors.muted)
            }
        }
    }
}
