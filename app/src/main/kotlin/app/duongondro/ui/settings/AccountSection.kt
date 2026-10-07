package app.duongondro.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.account.AccountManager
import app.duongondro.account.AccountStatus
import app.duongondro.ui.CardSection
import app.duongondro.ui.ListRow
import app.duongondro.ui.RowDivider
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings' account rows, minimal: who is signed in, the name friends see,
 * the recovery code's state, sync, and signing out on this phone. Without an
 * account, the two ways to get one.
 */
@Composable
fun AccountSection(
    accounts: AccountManager,
    openSignIn: () -> Unit,
    openNewAccount: () -> Unit,
    openRecoveryCode: () -> Unit,
) {
    val state by accounts.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val run = { work: suspend () -> Unit ->
        scope.launch {
            failed = false
            try { work() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        }
        Unit
    }

    when (state.status) {
        AccountStatus.NONE -> CardSection(stringResource(R.string.section_account), stringResource(R.string.account_footer_local)) {
            ListRow(stringResource(R.string.make_account), titleColor = Theme.colors.accent, semibold = true, onClick = openNewAccount)
            RowDivider()
            ListRow(stringResource(R.string.sign_in_existing), chevron = true, onClick = openSignIn)
        }
        AccountStatus.SIGNED_OUT -> CardSection(stringResource(R.string.section_account), stringResource(R.string.signed_out)) {
            ListRow(stringResource(R.string.sign_in_again), titleColor = Theme.colors.accent, semibold = true, onClick = openSignIn)
        }
        AccountStatus.NEEDS_KEYS -> CardSection(stringResource(R.string.section_account)) {
            // No sign-out here: the keys could not come back without a recovery code.
            ListRow(stringResource(R.string.finish_set_up), titleColor = Theme.colors.accent, semibold = true, onClick = openRecoveryCode)
        }
        AccountStatus.READY -> {
            val footer = when {
                state.refused > 0 -> pluralStringResource(R.plurals.sync_refused, state.refused, state.refused)
                state.unreadable > 0 -> pluralStringResource(R.plurals.sync_unreadable, state.unreadable, state.unreadable)
                state.offline -> stringResource(R.string.sync_offline)
                else -> null
            }
            CardSection(stringResource(R.string.section_account), footer) {
                (state.email ?: state.username)?.let {
                    ListRow(stringResource(R.string.account_signed_in_as), detail = it)
                    RowDivider()
                }
                ListRow(stringResource(R.string.account_name), detail = state.displayName, chevron = true, onClick = { naming = true })
                RowDivider()
                ListRow(stringResource(R.string.recovery_code),
                    detail = stringResource(if (state.recoveryUnconfirmed) R.string.recovery_not_confirmed else R.string.recovery_make_new),
                    detailColor = if (state.recoveryUnconfirmed) Theme.colors.destructive else Theme.colors.muted,
                    chevron = true, onClick = { replacing = true })
                RowDivider()
                ListRow(stringResource(R.string.sync), detail = syncDetail(state.syncing, state.lastSync),
                    onClick = { if (!state.syncing) run { accounts.syncNow() } })
                RowDivider()
                if (state.canSignOut) {
                    ListRow(stringResource(R.string.sign_out), titleColor = Theme.colors.destructive, onClick = { signingOut = true })
                } else {
                    // Signing out forgets the keys: first the recovery code must be stored and confirmed.
                    Column(Modifier.clickable(onClick = openRecoveryCode).padding(horizontal = Space.l, vertical = Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(stringResource(R.string.sign_out), style = Theme.type.body, color = Theme.colors.muted)
                        Text(stringResource(R.string.sign_out_needs_recovery), style = Theme.type.footnote, color = Theme.colors.accent)
                    }
                }
            }
        }
    }
    if (failed) {
        Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive,
            modifier = Modifier.padding(horizontal = Space.l))
    }

    if (naming) {
        var name by remember { mutableStateOf(state.displayName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.account_name)) },
            text = {
                Column {
                    Text(stringResource(R.string.account_name_detail), style = Theme.type.secondary, color = Theme.colors.soft,
                        modifier = Modifier.padding(bottom = Space.m))
                    OutlinedTextField(name, { name = it.take(64) }, singleLine = true, label = { Text(stringResource(R.string.name)) })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { naming = false; run { accounts.setDisplayName(name) } }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (replacing) {
        AlertDialog(
            onDismissRequest = { replacing = false },
            title = { Text(stringResource(R.string.recovery_new_title)) },
            text = { Text(stringResource(R.string.recovery_new_detail)) },
            confirmButton = {
                TextButton(onClick = { replacing = false; openRecoveryCode() }) { Text(stringResource(R.string.recovery_new_confirm)) }
            },
            dismissButton = { TextButton(onClick = { replacing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (signingOut) {
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_detail)) },
            confirmButton = {
                TextButton(onClick = { signingOut = false; run { accounts.signOut() } }) {
                    Text(stringResource(R.string.sign_out), color = Theme.colors.destructive)
                }
            },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun syncDetail(syncing: Boolean, last: java.time.Instant?): String {
    if (syncing) return stringResource(R.string.syncing)
    if (last == null) return stringResource(R.string.not_yet)
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val at = last.atZone(zone)
    val style = if (at.toLocalDate() == LocalDate.now(zone)) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    return style.withLocale(locale).format(at)
}
