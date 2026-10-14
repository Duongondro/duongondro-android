package app.duongondro.ui.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.core.sync.Social
import app.duongondro.model.AppModel
import app.duongondro.ui.CardSection
import app.duongondro.ui.ListRow
import app.duongondro.ui.RowDivider
import app.duongondro.ui.settings.Page
import app.duongondro.ui.settings.SwitchRow
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * One friend: their public streaks, whether you hear when they practise, a
 * poke, and unfriend, report and block (design: Social › Moderation; the
 * release checklist requires report and block). A changed key hides their
 * streaks and says why.
 */
@Composable
fun FriendDetailScreen(model: AppModel, friendId: String, back: () -> Unit) {
    val accounts = model.accounts ?: return
    val id = runCatching { UUID.fromString(friendId) }.getOrNull() ?: return
    val friends by accounts.friends.collectAsStateWithLifecycle()
    val now by model.now.collectAsStateWithLifecycle()
    val f = friends.friends.firstOrNull { it.user == id }
    // Opened afresh on this route (after process death): nothing fetched yet, once the account has loaded.
    val status = accounts.state.collectAsStateWithLifecycle().value.status
    LaunchedEffect(status) { if (!friends.loaded) accounts.refreshFriends() }
    // Gone (unfriended or blocked, here or elsewhere): back to the list.
    LaunchedEffect(f == null, friends.loaded) { if (f == null && friends.loaded) back() }
    if (f == null) {
        // Until the list arrives: the page with its way back, never a blank screen.
        Page("", back) {
            if (friends.failed) Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive,
                modifier = Modifier.padding(horizontal = Space.l))
        }
        return
    }
    val scope = rememberCoroutineScope()
    var failed by rememberSaveable { mutableStateOf(false) }
    var reported by rememberSaveable { mutableStateOf(false) }
    var confirmingUnfriend by rememberSaveable { mutableStateOf(false) }
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }
    var reporting by rememberSaveable { mutableStateOf(false) }
    val run = { work: suspend () -> Unit -> scope.launch { attempt({ failed = it }, work) }; Unit }

    Page(f.shownName(), back) {
        if (f.keyChanged) {
            CardSection(footer = stringResource(R.string.key_changed_detail)) {
                Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).padding(horizontal = Space.l),
                    horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Theme.colors.destructive, modifier = Modifier.size(Space.l))
                    Text(stringResource(R.string.their_key_changed), style = Theme.type.body, color = Theme.colors.destructive)
                }
            }
        } else {
            CardSection(stringResource(R.string.public_streaks), stringResource(R.string.public_streaks_footer)) {
                if (f.streaks.isEmpty()) ListRow(stringResource(R.string.none_shared), titleColor = Theme.colors.muted)
                f.streaks.forEachIndexed { i, s: Social.FriendStreak ->
                    if (i > 0) RowDivider()
                    val current = s.currentAt(now)
                    ListRow(practiceName(s.practice), detail = pluralStringResource(R.plurals.days_longest, current, current, s.longest))
                }
            }
        }
        CardSection(stringResource(R.string.notifications)) {
            SwitchRow(stringResource(R.string.tell_me_when), null, f.notifyDone, Modifier.padding(horizontal = Space.l, vertical = Space.s)) { on ->
                run { accounts.setNotifyDone(f.user, on) }
            }
        }
        val poked = friends.isPoked(f.user, now)
        CardSection(footer = if (reported) stringResource(R.string.reported) else null) {
            ListRow(stringResource(if (poked) R.string.poked else R.string.poke),
                titleColor = if (poked) Theme.colors.muted else Theme.colors.accent, semibold = !poked,
                onClick = if (poked) null else ({ run { accounts.poke(f.user) } }))
            RowDivider()
            ListRow(stringResource(R.string.unfriend), onClick = { confirmingUnfriend = true })
            RowDivider()
            ListRow(stringResource(R.string.report), onClick = { reporting = true })
            RowDivider()
            ListRow(stringResource(R.string.block), titleColor = Theme.colors.destructive, onClick = { confirmingBlock = true })
        }
        if (failed) {
            Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive,
                modifier = Modifier.padding(horizontal = Space.l))
        }
    }

    if (confirmingUnfriend) Confirm(R.string.unfriend_title, R.string.unfriend_detail, R.string.unfriend, { confirmingUnfriend = false }) {
        confirmingUnfriend = false
        run { accounts.unfriend(f.user) }
    }
    if (confirmingBlock) Confirm(R.string.block_title, R.string.block_detail, R.string.block, { confirmingBlock = false }) {
        confirmingBlock = false
        run { accounts.block(f.user) }
    }
    if (reporting) {
        var reason by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text(stringResource(R.string.report)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Text(stringResource(R.string.report_detail), style = Theme.type.secondary, color = Theme.colors.soft)
                    OutlinedTextField(reason, { reason = it.take(Social.REPORT_LIMIT) }, label = { Text(stringResource(R.string.report_hint)) },
                        minLines = 3, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    reporting = false
                    val text = reason
                    scope.launch { reported = attempt({ failed = it }) { accounts.report(f.user, text) } }
                }) { Text(stringResource(R.string.send)) }
            },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Confirm(title: Int, detail: Int, action: Int, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(detail)) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(action), color = Theme.colors.destructive) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
