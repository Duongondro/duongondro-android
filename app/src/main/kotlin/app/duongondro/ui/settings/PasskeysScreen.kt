package app.duongondro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.account.AccountManager
import app.duongondro.account.PasskeyRemoval
import app.duongondro.account.PasskeyResult
import app.duongondro.account.UnlockSetUp
import app.duongondro.core.api.Passkey
import app.duongondro.core.api.PasskeyNames
import app.duongondro.core.sync.SyncTime
import app.duongondro.ui.CardSection
import app.duongondro.ui.FilledAction
import app.duongondro.ui.RowDivider
import app.duongondro.ui.TextAction
import app.duongondro.ui.card
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings › Passkeys (design decision 25; mockup "Android: Passkeys"): the
 * account's passkeys with their names, when each was added and last used and
 * whether it unlocks the counts on a new phone (it holds a box of U), and
 * Add, Rename and Remove. A passkey is named after its password manager until
 * the person names it.
 */
@Composable
fun PasskeysScreen(accounts: AccountManager, back: () -> Unit) {
    val state by accounts.passkeys.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current
    var problem by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Passkey?>(null) }
    var removing by remember { mutableStateOf<Passkey?>(null) }
    var replacing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { accounts.refreshPasskeys() }
    val run = { work: suspend () -> Unit ->
        if (!busy) {
            busy = true
            problem = null
            scope.launch {
                try { work() } catch (e: CancellationException) { throw e } catch (_: Exception) { problem = R.string.account_error } finally { busy = false }
            }
        }
        Unit
    }
    val defaultName = stringResource(R.string.passkey_default_name)
    fun nameOf(p: Passkey) = p.name?.takeIf { it.isNotBlank() } ?: PasskeyNames.of(p.aaguid) ?: defaultName

    Page(stringResource(R.string.passkeys_title), back) {
        Text(stringResource(R.string.passkeys_detail), style = Theme.type.secondary, color = Theme.colors.soft,
            modifier = Modifier.padding(horizontal = Space.l))
        if (state.offerUnlock) UnlockCard(accounts)
        if (state.passkeys.isNotEmpty()) {
            CardSection {
                state.passkeys.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    PasskeyRow(nameOf(p), p, rename = { renaming = p }, remove = { removing = p })
                }
            }
        } else if (state.failed) {
            Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive,
                modifier = Modifier.padding(horizontal = Space.l))
        }
        problem?.let {
            Text(stringResource(it), style = Theme.type.footnote, color = Theme.colors.destructive, modifier = Modifier.padding(horizontal = Space.l))
        }
        FilledAction(stringResource(R.string.passkey_add), enabled = !busy) {
            run {
                when (accounts.createPasskey(activity, profile = null)) {
                    PasskeyResult.Unavailable -> problem = R.string.passkey_unavailable_add
                    else -> Unit
                }
            }
        }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.passkey_rename_title)) },
            text = {
                OutlinedTextField(name, { name = it.take(64) }, singleLine = true, label = { Text(stringResource(R.string.name)) },
                    placeholder = { Text(PasskeyNames.of(p.aaguid) ?: defaultName) })
            },
            confirmButton = {
                TextButton(onClick = { renaming = null; run { accounts.renamePasskey(p.id, name) } }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.passkey_remove_title, nameOf(p))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(stringResource(R.string.passkey_remove_detail))
                    if (p.current) Text(stringResource(R.string.passkey_remove_current))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    run {
                        when (accounts.removePasskey(p)) {
                            PasskeyRemoval.LastWay -> problem = R.string.passkey_last_way
                            PasskeyRemoval.SignedOut -> back()
                            PasskeyRemoval.RemovedUnlocking -> replacing = true
                            PasskeyRemoval.Removed -> Unit
                        }
                    }
                }) { Text(stringResource(R.string.passkey_remove), color = Theme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Removing a passkey that could unlock is not revocation: offer a new unlock key (duongondro-api#26).
    if (replacing) {
        AlertDialog(
            onDismissRequest = { replacing = false },
            title = { Text(stringResource(R.string.unlock_replace_title)) },
            text = { Text(stringResource(R.string.unlock_replace_detail)) },
            confirmButton = {
                TextButton(onClick = { replacing = false; run { accounts.replaceUnlockKey() } }) {
                    Text(stringResource(R.string.unlock_replace_action))
                }
            },
            dismissButton = { TextButton(onClick = { replacing = false }) { Text(stringResource(R.string.unlock_card_later)) } },
        )
    }
}

@Composable
private fun PasskeyRow(name: String, p: Passkey, rename: () -> Unit, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(name, style = Theme.type.body, color = Theme.colors.ink)
            Text(usage(p), style = Theme.type.footnote, color = Theme.colors.muted)
            if (p.canUnlock) Text(stringResource(R.string.passkey_unlocks), style = Theme.type.footnote, color = Theme.colors.accent)
        }
        IconButton(onClick = rename, modifier = Modifier.size(Size.minTap)) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.passkey_rename_named, name), tint = Theme.colors.soft)
        }
        IconButton(onClick = remove, modifier = Modifier.size(Size.minTap)) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.passkey_remove_named, name), tint = Theme.colors.soft)
        }
    }
}

/** "Added 7 Oct 2026 · last used today", in the phone's locale. */
@Composable
private fun usage(p: Passkey): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val added = SyncTime.parse(p.createdAt)?.atZone(zone)?.toLocalDate()?.let(format::format) ?: ""
    val used = p.lastUsedAt?.let(SyncTime::parse)?.atZone(zone)?.toLocalDate()
    return when {
        used == null -> stringResource(R.string.passkey_added, added)
        used == LocalDate.now(zone) -> stringResource(R.string.passkey_added_used, added, stringResource(R.string.passkey_today))
        else -> stringResource(R.string.passkey_added_used, added, format.format(used))
    }
}

/**
 * The retrofit (design: Keys › Passkeys that unlock the counts), at a calm
 * moment, never over a count: one passkey ceremony without allowCredentials,
 * then U sealed for whichever passkey answered. A passkey that gives no PRF
 * output is offered a new passkey instead; the old one keeps signing in.
 */
@Composable
fun UnlockCard(accounts: AccountManager) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<UnlockSetUp?>(null) }
    var failed by remember { mutableStateOf(false) }
    val run = { work: suspend () -> Unit ->
        if (!busy) {
            busy = true
            failed = false
            scope.launch {
                try { work() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true } finally { busy = false }
            }
        }
        Unit
    }
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(stringResource(R.string.unlock_card_title), style = Theme.type.cardTitle, color = Theme.colors.ink)
        val noOutput = outcome == UnlockSetUp.NoOutput
        Text(stringResource(if (noOutput) R.string.unlock_card_no_output else R.string.unlock_card_detail),
            style = Theme.type.secondary, color = Theme.colors.soft)
        if (outcome == UnlockSetUp.OtherAccount) Text(stringResource(R.string.passkey_other_account), style = Theme.type.footnote, color = Theme.colors.destructive)
        if (failed) Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive)
        if (noOutput) {
            FilledAction(stringResource(R.string.unlock_card_new_passkey), enabled = !busy) {
                run { if (accounts.createPasskey(activity, profile = null) == PasskeyResult.Saved) outcome = null }
            }
        } else {
            FilledAction(stringResource(R.string.unlock_card_action), enabled = !busy) {
                run { outcome = accounts.letPasskeyUnlock(activity) }
            }
        }
        TextAction(stringResource(R.string.unlock_card_later)) { accounts.dismissUnlockOffer() }
    }
}
