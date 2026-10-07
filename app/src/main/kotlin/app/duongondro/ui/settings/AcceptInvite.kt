package app.duongondro.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.account.AcceptCheck
import app.duongondro.account.AccountManager
import app.duongondro.account.AccountStatus
import app.duongondro.core.api.ApiError
import app.duongondro.core.sync.Invitation
import app.duongondro.model.AppModel
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface Accepting {
    data object Checking : Accepting
    class Confirm(val checked: Invitation.Checked) : Accepting
    data object Working : Accepting
    class Done(val name: String?) : Accepting
    class Problem(val message: Int) : Accepting
}

/**
 * An invitation link opened (or scanned) by an account that already has its
 * keys, as iOS's AcceptInviteView: checked first (the signature, then the
 * link's MAC), then accepted on confirmation, which makes the two friends.
 * Before an account exists, the link goes to sign-up instead.
 */
@Composable
fun AcceptInviteDialog(model: AppModel, accounts: AccountManager) {
    val code by model.inviteCode.collectAsStateWithLifecycle()
    val state by accounts.state.collectAsStateWithLifecycle()
    val shownCode by accounts.shownCode.collectAsStateWithLifecycle()
    val signingUp by accounts.signingUpWithInvite.collectAsStateWithLifecycle()
    val opened = code ?: return
    // Not while a sign-up from this link finishes, nor over the recovery code (iOS waits for it too).
    if (state.status != AccountStatus.READY || shownCode != null || signingUp) return
    val scope = rememberCoroutineScope()
    var step by remember(opened) { mutableStateOf<Accepting>(Accepting.Checking) }
    LaunchedEffect(opened) {
        step = try {
            when (val c = accounts.checkToAccept(opened)) {
                is AcceptCheck.Ready -> Accepting.Confirm(c.checked)
                AcceptCheck.Own -> Accepting.Problem(R.string.accept_own)
                AcceptCheck.Gone -> Accepting.Problem(R.string.accept_gone)
                AcceptCheck.NotAuthentic -> Accepting.Problem(R.string.invite_not_authentic)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Accepting.Problem(R.string.account_error)
        }
    }
    val close = { model.usedInvite() }
    when (val s = step) {
        Accepting.Checking, Accepting.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.accept_title)) },
            text = { CircularProgressIndicator(color = Theme.colors.accent) },
            confirmButton = {},
        )
        is Accepting.Confirm -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.accept_title)) },
            text = { Text(stringResource(R.string.accept_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    step = Accepting.Working
                    scope.launch {
                        step = try {
                            Accepting.Done(accounts.accept(s.checked))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: ApiError.NotFound) {
                            Accepting.Problem(R.string.accept_gone)
                        } catch (_: Exception) {
                            Accepting.Problem(R.string.account_error)
                        }
                    }
                }) { Text(stringResource(R.string.accept_confirm)) }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.not_now)) } },
        )
        is Accepting.Done -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.accept_done_title)) },
            text = { Text(s.name?.let { stringResource(R.string.accept_done_named, it) } ?: stringResource(R.string.accept_done)) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.ok)) } },
        )
        is Accepting.Problem -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.accept_title)) },
            text = { Text(stringResource(s.message)) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.ok)) } },
        )
    }
}
