package app.duongondro.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import app.duongondro.R
import app.duongondro.account.AccountManager
import app.duongondro.account.Crockford
import app.duongondro.account.OpenInvite
import app.duongondro.core.qr.ECC
import app.duongondro.core.qr.QRCode
import app.duongondro.core.sync.MadeInvite
import app.duongondro.ui.CardSection
import app.duongondro.ui.FilledAction
import app.duongondro.ui.ListRow
import app.duongondro.ui.OutlinedAction
import app.duongondro.ui.RowDivider
import app.duongondro.ui.theme.Radius
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil

private enum class Shown { Loading, Invite, None, Failed }

/**
 * The mockup's Invite screen (Invite.dc.html) in Material 3: the link as a QR
 * code on a round burgundy badge, the same 24 characters as a code to type,
 * Share and Copy, and the account's open invitations with Revoke. Reached from
 * Settings › Account, only with an account that has its keys.
 */
@Composable
fun InviteScreen(accounts: AccountManager, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<MadeInvite?>(null) }
    var shown by remember { mutableStateOf(Shown.Loading) }
    var open by remember { mutableStateOf<List<OpenInvite>?>(null) }
    var revoking by remember { mutableStateOf<OpenInvite?>(null) }
    var failed by remember { mutableStateOf(false) }

    suspend fun refreshOpen() {
        try {
            open = accounts.openInvites()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    fun make() {
        scope.launch {
            shown = Shown.Loading
            failed = false
            shown = try {
                invite = accounts.invite()
                Shown.Invite
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Shown.Failed
            }
            refreshOpen()
        }
    }

    LaunchedEffect(Unit) {
        // The one on show was revoked here: no new one is minted unasked, even after
        // leaving and coming back, until "Make an invitation".
        if (accounts.inviteWithdrawn) {
            shown = Shown.None
            refreshOpen()
        } else make()
    }

    Page(stringResource(R.string.invite_screen_title), back) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
            val current = invite
            when {
                // The list below says what is open; a new invitation is one tap away.
                shown == Shown.None -> FilledAction(stringResource(R.string.invite_make)) { make() }
                shown == Shown.Failed -> {
                    QRBadge(null, loading = false)
                    Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive, textAlign = TextAlign.Center)
                    FilledAction(stringResource(R.string.try_again)) { make() }
                }
                shown == Shown.Loading || current == null -> QRBadge(null, loading = true)
                else -> InviteShown(current)
            }
        }

        open?.let { list ->
            CardSection(stringResource(R.string.invite_open), stringResource(R.string.invite_open_footer)) {
                if (list.isEmpty()) ListRow(stringResource(R.string.invite_open_none), titleColor = Theme.colors.muted)
                list.forEachIndexed { i, o ->
                    if (i > 0) RowDivider()
                    OpenInviteRow(o, onScreen = o.id == invite?.id && shown == Shown.Invite) { revoking = o }
                }
            }
        }
        // The Failed state says it above already.
        if (failed && shown != Shown.Failed) {
            Text(stringResource(R.string.account_error), style = Theme.type.footnote, color = Theme.colors.destructive,
                modifier = Modifier.padding(horizontal = Space.l))
        }
    }

    revoking?.let { o ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.invite_revoke_title)) },
            text = { Text(stringResource(R.string.invite_revoke_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    scope.launch {
                        failed = false
                        try {
                            accounts.revokeInvite(o.id)
                            if (invite?.id == o.id) {
                                invite = null
                                shown = Shown.None
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed = true
                        }
                        refreshOpen()
                    }
                }) { Text(stringResource(R.string.invite_revoke), color = Theme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The badge, what it is for and how long it works, the code to type, and Share and Copy. */
@Composable
private fun InviteShown(invite: MadeInvite) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    val days = ceil(Duration.between(Instant.now(), invite.expiresAt).toMinutes() / MINUTES_PER_DAY).toInt().coerceAtLeast(1)
    val shareText = stringResource(R.string.invite_share_text, invite.link)
    val clipLabel = stringResource(R.string.invite_label)

    QRBadge(invite.qrText, loading = false)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(stringResource(R.string.invite_scan), style = Theme.type.title, color = Theme.colors.ink, textAlign = TextAlign.Center)
        Text(pluralStringResource(R.plurals.invite_works_for, days, days), style = Theme.type.subtitle, color = Theme.colors.muted,
            textAlign = TextAlign.Center)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(stringResource(R.string.invite_code_caption), style = Theme.type.secondary, color = Theme.colors.muted, textAlign = TextAlign.Center)
        // Six groups of four, three to a line, as the recovery code is shown.
        Crockford.grouped(invite.code).chunked(GROUPS_PER_LINE).forEach { line ->
            Text(line.joinToString(" "), style = Theme.type.code, color = Theme.colors.ink, textAlign = TextAlign.Center)
        }
    }
    FilledAction(stringResource(R.string.invite_share)) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
        context.startActivity(Intent.createChooser(send, null))
    }
    OutlinedAction(stringResource(if (copied) R.string.copied else R.string.invite_copy)) {
        scope.launch {
            // Anyone with the code can join and befriend: keep it out of clipboard previews.
            val clip = ClipData.newPlainText(clipLabel, invite.code).apply {
                description.extras = PersistableBundle().apply {
                    putBoolean(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else IS_SENSITIVE, true)
                }
            }
            clipboard.setClipEntry(clip.toClipEntry())
            copied = true
        }
    }
}

/** One open invitation: its id (the code's first two groups), until when it works, and Revoke. */
@Composable
private fun OpenInviteRow(invite: OpenInvite, onScreen: Boolean, revoke: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val until = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(invite.expiresAt.atZone(ZoneId.systemDefault()))
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTap).padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(Crockford.grouped(invite.id).joinToString(" "), style = Theme.type.mono, color = Theme.colors.ink)
            val detail = stringResource(R.string.invite_open_until, until) +
                if (onScreen) " · " + stringResource(R.string.invite_open_shown) else ""
            Text(detail, style = Theme.type.footnote, color = Theme.colors.muted)
        }
        TextButton(onClick = revoke) { Text(stringResource(R.string.invite_revoke), color = Theme.colors.destructive) }
    }
}

/**
 * A burgundy disc with a white tile holding the code, and the app's emblem
 * (the endless knot) above it, outside the code's quiet zone.
 */
@Composable
private fun QRBadge(text: String?, loading: Boolean) {
    val code = remember(text) { text?.let { runCatching { QRCode.encode(it, ECC.MEDIUM) }.getOrNull() } }
    val description = stringResource(R.string.invite_qr_description)
    val ink = Theme.colors.qrInk
    Box(Modifier.size(Size.qrBadge).background(Theme.colors.hero, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(Size.qrTile).background(Theme.colors.qrGround, RoundedCornerShape(Radius.bigButton)), contentAlignment = Alignment.Center) {
            when {
                code != null -> {
                    // Four modules of white all round (the spec's quiet zone): 29 + 8 modules across the tile.
                    val side = Size.qrTile * code.size / (code.size + QUIET_ZONE * 2)
                    Canvas(Modifier.size(side).semantics { contentDescription = description }) { drawModules(code, ink) }
                }
                loading -> CircularProgressIndicator(color = ink)
            }
        }
        // The endless knot, as on Welcome, centred in the band above the tile.
        Box(Modifier.align(Alignment.TopCenter).height((Size.qrBadge - Size.qrTile) / 2), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.emblem), contentDescription = null, modifier = Modifier.width(Size.badgeEmblem))
        }
    }
}

/** The modules as dots, the three finder patterns as rounded squares. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawModules(code: QRCode, ink: Color) {
    val m = size.width / code.size
    val r = m * (1 - 2 * DOT_INSET) / 2
    for (y in 0 until code.size) for (x in 0 until code.size) {
        if (code[x, y] && !code.isFinderModule(x, y)) drawCircle(ink, r, Offset((x + 0.5f) * m, (y + 0.5f) * m))
    }
    for ((fx, fy) in listOf(0 to 0, code.size - 7 to 0, 0 to code.size - 7)) {
        drawRoundRect(ink, Offset((fx + 0.5f) * m, (fy + 0.5f) * m), GeometrySize(6 * m, 6 * m), CornerRadius(m * 1.6f), style = Stroke(m))
        drawRoundRect(ink, Offset((fx + 2) * m, (fy + 2) * m), GeometrySize(3 * m, 3 * m), CornerRadius(m * 0.9f))
    }
}

private const val QUIET_ZONE = 4
private const val DOT_INSET = 0.08f
private const val GROUPS_PER_LINE = 3
private const val COPIED_MS = 2_000L
private const val MINUTES_PER_DAY = 1440.0
/** ClipDescription.EXTRA_IS_SENSITIVE before API 33, which some keyboards and launchers read. */
private const val IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
