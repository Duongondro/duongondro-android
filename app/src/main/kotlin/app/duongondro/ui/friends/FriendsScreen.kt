package app.duongondro.ui.friends

import android.icu.text.DateFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.account.AccountManager
import app.duongondro.account.AccountStatus
import app.duongondro.account.Crockford
import app.duongondro.core.Catalogue
import app.duongondro.core.sync.FriendsBoard
import app.duongondro.core.sync.Social
import app.duongondro.model.AppModel
import app.duongondro.push.Push
import app.duongondro.push.PushAskCard
import app.duongondro.push.rememberPushPermission
import app.duongondro.ui.CardSection
import app.duongondro.ui.GenderedString
import app.duongondro.ui.ListRow
import app.duongondro.ui.RowDivider
import app.duongondro.ui.TextAction
import app.duongondro.ui.genderOf
import app.duongondro.ui.shownName
import app.duongondro.ui.stringResource
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The Friends tab (mockups: Android Friends, iOS Friends): today's news from
 * friends' public streaks, the leaderboard of tracked days, your friends, and
 * Invite. Without an account with keys, a short explainer and the way to one.
 */
@Composable
fun FriendsScreen(
    model: AppModel,
    openFriend: (UUID) -> Unit,
    openInvite: () -> Unit,
    openNewAccount: () -> Unit,
    openSignIn: () -> Unit,
    openRecoveryCode: () -> Unit,
) {
    val accounts = model.accounts
    val state = accounts?.state?.collectAsStateWithLifecycle()?.value
    val ready = state?.status == AccountStatus.READY
    Box(Modifier.fillMaxSize().background(Theme.colors.ground)) {
        if (accounts == null || !ready) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl).padding(top = Space.l, bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Title()
                NoAccount(state?.status ?: AccountStatus.NONE, openNewAccount, openSignIn, openRecoveryCode)
            }
        } else {
            Friends(model, accounts, openFriend)
            ExtendedFloatingActionButton(
                onClick = openInvite,
                icon = { Icon(painterResource(R.drawable.ic_invite), contentDescription = null) },
                text = { Text(stringResource(R.string.invite_short), style = Theme.type.button.copy(fontSize = Theme.type.body.fontSize)) },
                shape = MaterialTheme.shapes.medium,
                containerColor = Theme.colors.accent,
                contentColor = Theme.colors.onAccent,
                modifier = Modifier.align(Alignment.BottomEnd).padding(Space.l),
            )
        }
    }
}

@Composable
private fun Title() {
    Text(stringResource(R.string.tab_friends), style = Theme.type.largeTitle, color = Theme.colors.ink,
        modifier = Modifier.padding(horizontal = Space.xs).semantics { heading() })
}

/** Local mode, or an account not ready here: what friends see, and the way to an account. */
@Composable
private fun NoAccount(status: AccountStatus, openNewAccount: () -> Unit, openSignIn: () -> Unit, openRecoveryCode: () -> Unit) {
    CardSection(footer = stringResource(R.string.friends_local_detail)) {
        ListRow(stringResource(R.string.friends_local_title))
        RowDivider()
        when (status) {
            AccountStatus.NONE -> {
                ListRow(stringResource(R.string.make_account), titleColor = Theme.colors.accent, semibold = true, onClick = openNewAccount)
                RowDivider()
                ListRow(stringResource(R.string.sign_in_existing), chevron = true, onClick = openSignIn)
            }
            AccountStatus.SIGNED_OUT ->
                ListRow(stringResource(R.string.sign_in_again), titleColor = Theme.colors.accent, semibold = true, onClick = openSignIn)
            AccountStatus.NEEDS_KEYS, AccountStatus.READY ->
                ListRow(stringResource(R.string.finish_set_up), titleColor = Theme.colors.accent, semibold = true, onClick = openRecoveryCode)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Friends(model: AppModel, accounts: AccountManager, openFriend: (UUID) -> Unit) {
    val friends by accounts.friends.collectAsStateWithLifecycle()
    val now by model.now.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pasting by rememberSaveable { mutableStateOf(false) }
    var pokeFailed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Asked here, on a visit with a friend to be nagged by (design: Onboarding ›
    // the notification permission), not at sign-up.
    var offerPush by remember { mutableStateOf(Push.offersCard(context)) }
    val askPush = rememberPushPermission(model) { offerPush = Push.offersCard(context) }
    // Fresh on every return to the tab, and on pulling down.
    LifecycleResumeEffect(Unit) {
        scope.launch { accounts.refreshFriends() }
        offerPush = Push.offersCard(context)
        onPauseOrDispose { }
    }
    val news = FriendsBoard.news(friends.friends, now)
    val leaders = FriendsBoard.leaders(friends.friends, now)
    PullToRefreshBox(isRefreshing = friends.loading && friends.loaded, onRefresh = { scope.launch { accounts.refreshFriends() } },
        modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl).padding(top = Space.l, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Title()
            friends.friends.firstOrNull()?.takeIf { offerPush }?.let { first ->
                PushAskCard(first.shownName(), askPush) {
                    Push.dismissCard(context)
                    offerPush = false
                }
            }
            if (news.isNotEmpty()) {
                CardSection(stringResource(R.string.today)) {
                    news.forEachIndexed { i, item ->
                        if (i > 0) RowDivider()
                        NewsRow(item, now, friends.isPoked(item.friend.user, now)) {
                            scope.launch {
                                // So they can poke back; only once the poke went.
                                if (attempt({ pokeFailed = it }) { accounts.poke(item.friend.user) }) askPush()
                            }
                        }
                    }
                }
            }
            if (leaders.isNotEmpty()) {
                CardSection(stringResource(R.string.friends_leaderboard)) {
                    leaders.forEachIndexed { i, l ->
                        if (i > 0) RowDivider()
                        LeaderRow(l)
                    }
                }
            }
            if (friends.loaded && friends.friends.isEmpty()) {
                Text(stringResource(R.string.friends_none), style = Theme.type.secondary, color = Theme.colors.muted,
                    modifier = Modifier.padding(horizontal = Space.l))
            }
            if (friends.friends.isNotEmpty()) {
                CardSection(stringResource(R.string.friends_yours)) {
                    friends.friends.forEachIndexed { i, f ->
                        if (i > 0) RowDivider()
                        FriendRow(f) { openFriend(f.user) }
                    }
                }
            }
            if (friends.failed || pokeFailed) {
                Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive,
                    modifier = Modifier.padding(horizontal = Space.l))
            }
            TextAction(stringResource(R.string.paste_invite)) { pasting = true }
            // Room for the Invite button over the end of the list.
            Spacer(Modifier.height(Size.button + Space.l))
        }
    }
    if (pasting) PasteInviteDialog(onDismiss = { pasting = false }) { code ->
        pasting = false
        model.openedInvite(code)
    }
}

/** The name a friend chose, or "A friend" when they gave none. */
@Composable
fun Social.FriendView.shownName(): String = displayName.ifBlank { stringResource(R.string.a_friend) }

/** A built-in practice's name in this language; a friend's own practice stays unnamed (its name is not published). */
@Composable
fun practiceName(id: String): String =
    Catalogue.builtIn.firstOrNull { it.id == id }?.shownName() ?: stringResource(R.string.their_own_practice)

/** A friend's initial on a soft disc, as in the mockups. */
@Composable
fun Initial(name: String) {
    Box(Modifier.size(Size.minTap).background(Theme.colors.track, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "·", style = Theme.type.button, color = Theme.colors.accent)
    }
}

@Composable
private fun NewsRow(item: FriendsBoard.NewsItem, now: Instant, poked: Boolean, poke: () -> Unit) {
    val name = item.friend.shownName()
    val practice = practiceName(item.streak.practice)
    val gender = genderOf(item.friend.gender)
    // Without a name the sentence says "a friend" itself, in the right case and gender.
    val unnamed = item.friend.displayName.isBlank()
    val title = when {
        item.doneToday && unnamed -> stringResource(GenderedString.FriendFinishedUnnamed, gender, practice)
        item.doneToday -> stringResource(GenderedString.FriendFinished, gender, name, practice)
        unnamed -> stringResource(GenderedString.FriendNotYetUnnamed, gender)
        else -> stringResource(GenderedString.FriendNotYet, gender, name)
    }
    val locale = LocalConfiguration.current.locales[0]
    val detail = if (item.doneToday) {
        // Statements from this app carry their sending time as seq.
        val sent = Instant.ofEpochMilli(item.streak.seq)
        val ago = if (!sent.isAfter(now) && Duration.between(sent, now) < Duration.ofDays(1)) " · " + relative(sent, now, ULocale.forLocale(locale)) else ""
        stringResource(R.string.friend_day_n, item.streak.current) + ago
    } else {
        // In this phone's clock: "at midnight" only when it is this phone's midnight too.
        val deadline = item.streak.deadline.atZone(ZoneId.systemDefault())
        val ends = if (deadline.hour == 0 && deadline.minute == 0 && Duration.between(now, item.streak.deadline) <= Duration.ofDays(1)) {
            stringResource(R.string.ends_at_midnight)
        } else {
            stringResource(R.string.ends_at, DateFormat.getInstanceForSkeleton("EEEjmm", locale).format(java.util.Date.from(item.streak.deadline)))
        }
        pluralStringResource(R.plurals.friend_streak_ends, item.streak.current, practice, item.streak.current, ends)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        Initial(name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(title, style = Theme.type.body.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.ink)
            Text(detail, style = Theme.type.footnote, color = Theme.colors.muted)
        }
        if (!item.doneToday) {
            val label = if (unnamed) stringResource(if (poked) GenderedString.PokedUnnamed else GenderedString.PokeUnnamed, gender)
            else stringResource(if (poked) R.string.poked_named else R.string.poke_named, name)
            FilledTonalButton(
                onClick = poke, enabled = !poked, shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Theme.colors.streakCard, contentColor = Theme.colors.accent,
                    disabledContainerColor = Theme.colors.softFill, disabledContentColor = Theme.colors.muted),
                modifier = Modifier.heightIn(min = Size.minTap).semantics { contentDescription = label },
            ) { Text(stringResource(if (poked) R.string.poked else R.string.poke), style = Theme.type.button.copy(fontSize = Theme.type.footnote.fontSize)) }
        }
    }
}

/** "12 minutes ago", "3 hours ago", "now", in the app's language. */
private fun relative(at: Instant, now: Instant, locale: ULocale): String {
    val f = RelativeDateTimeFormatter.getInstance(locale)
    val minutes = Duration.between(at, now).toMinutes()
    return when {
        minutes < 1 -> f.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        minutes < 60 -> f.format(minutes.toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        else -> f.format((minutes / 60).toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.HOURS)
    }
}

@Composable
private fun LeaderRow(l: FriendsBoard.Leader) {
    Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).padding(horizontal = Space.l, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Text("${l.friend.shownName()} · ${practiceName(l.streak.practice)}", Modifier.weight(1f),
            style = Theme.type.body.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.ink)
        Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.flame, modifier = Modifier.size(Space.l))
        Text("${l.streak.current}", style = Theme.type.body.copy(fontWeight = FontWeight.Bold), color = Theme.colors.flameText)
    }
}

@Composable
private fun FriendRow(f: Social.FriendView, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Size.minTap).clickable(onClick = open).padding(horizontal = Space.l, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Text(f.shownName(), Modifier.weight(1f), style = Theme.type.body, color = Theme.colors.ink)
        if (f.keyChanged) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Theme.colors.destructive, modifier = Modifier.size(Space.l))
            Text(stringResource(R.string.key_changed), style = Theme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.destructive)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
    }
}

/** "Paste invite": for a link that arrived where tapping it does not open the app. */
@Composable
private fun PasteInviteDialog(onDismiss: () -> Unit, accept: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val code = Crockford.normalise(text, Crockford.INVITE_LENGTH).takeIf { it.length == Crockford.INVITE_LENGTH }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_invite_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(stringResource(R.string.paste_invite_detail), style = Theme.type.secondary, color = Theme.colors.soft)
                OutlinedTextField(text, { text = it.take(256) }, singleLine = true, textStyle = Theme.type.mono,
                    placeholder = { Text("https://duongondro.app/i/…") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = code != null, onClick = { code?.let(accept) }) { Text(stringResource(R.string.continue_)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Runs a friend action, turning a failure into [failed] rather than a crash. */
internal suspend fun attempt(failed: (Boolean) -> Unit, work: suspend () -> Unit): Boolean = try {
    failed(false)
    work()
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    failed(true)
    false
}
