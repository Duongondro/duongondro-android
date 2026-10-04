package app.duongondro.ui.practice

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.core.PendingLog
import app.duongondro.core.TrackedPractice
import app.duongondro.model.AfterMidnightPrompt
import app.duongondro.model.AppModel
import app.duongondro.ui.PracticeName
import app.duongondro.ui.card
import app.duongondro.ui.grouped
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * A practice's own screen: the only place counts are logged. Each +mala opens
 * a few seconds' Undo; the session is written only when that window closes.
 */
@Composable
fun PracticeScreen(model: AppModel, practiceId: String, back: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val pending by model.pending.collectAsStateWithLifecycle()
    val started by model.started.collectAsStateWithLifecycle()
    val now by model.now.collectAsStateWithLifecycle()
    val practice = snapshot.practices.firstOrNull { it.id == practiceId }
    val view = LocalView.current
    var askAmount by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Theme.colors.ground)) {
        IconButton(onClick = back, modifier = Modifier.padding(Space.xs)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        if (practice == null) {
            Text(stringResource(R.string.practice_gone), color = Theme.colors.muted, modifier = Modifier.padding(Space.xl))
            return
        }
        val tap = { amount: Int ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            model.add(amount, practice.id)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl).padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PracticeName(practice.practice, large = true)
            Stats(model, practice, now)
            StartRow(started[practice.id], onStart = { model.start(practice.id) }, onCancel = { model.cancelStart(practice.id) })
            val mine = pending?.takeIf { it.practiceId == practice.id }
            if (practice.streakOnly) {
                val done = model.practisedToday(practice.id, now)
                BigButton(stringResource(if (done) R.string.done_today else R.string.mark_today_done), Icons.Filled.Check,
                    enabled = !done && mine == null) { tap(0) }
            } else {
                val mala = model.malaSize(practice)
                val label = stringResource(R.string.add_one_mala, mala)
                BigButton("+$mala", null, modifier = Modifier.semantics { contentDescription = label }) { tap(mala) }
                TextButton(onClick = { askAmount = true }) { Text(stringResource(R.string.add_amount)) }
            }
            // Below the button, so it never moves under the thumb mid-count.
            mine?.let { UndoBar(it, practice.streakOnly) { model.undo() } }
        }
    }
    if (askAmount && practice != null) {
        AmountDialog(onDismiss = { askAmount = false }) { amount ->
            askAmount = false
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            model.add(amount, practice.id)
        }
    }
}

@Composable
private fun Stats(model: AppModel, p: TrackedPractice, now: Instant) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val sessions = snapshot.sessionsOf(p.id)
    val streak = model.streak(p.id, now)
    Column(Modifier.fillMaxWidth().card(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s)) {
        val rounds = p.rounds(sessions)
        val target = p.practice.target
        if (rounds != null && target != null) {
            Text(rounds.inRound.grouped(), style = MaterialTheme.typography.displayMedium)
            Text(stringResource(R.string.round_progress, rounds.round, rounds.inRound.grouped(), target.grouped()), color = Theme.colors.muted)
            LinearProgressIndicator(progress = { rounds.inRound.toFloat() / target }, modifier = Modifier.fillMaxWidth(),
                color = Theme.colors.accent, trackColor = Theme.colors.cardBorder)
            if (rounds.round > 1) {
                Text(stringResource(R.string.in_all_rounds, rounds.lifetime.grouped()), style = MaterialTheme.typography.bodySmall, color = Theme.colors.muted)
            }
        } else if (!p.streakOnly) {
            Text(p.lifetime(sessions).grouped(), style = MaterialTheme.typography.displayMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.flame)
            Text(pluralStringResource(R.plurals.days, streak.current, streak.current), style = MaterialTheme.typography.titleMedium)
            if (streak.longest > streak.current) {
                Text(pluralStringResource(R.plurals.longest_days, streak.longest, streak.longest), color = Theme.colors.muted)
            }
        }
    }
}

/** Start records the exact start, so the session needs no estimate. */
@Composable
private fun StartRow(started: Instant?, onStart: () -> Unit, onCancel: () -> Unit) {
    if (started != null) {
        Row(Modifier.fillMaxWidth().card(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.started_at, started.shortTime()), modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    } else {
        OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text(stringResource(R.string.start), modifier = Modifier.padding(start = Space.s))
        }
    }
}

@Composable
private fun UndoBar(pending: PendingLog, streakOnly: Boolean, undo: () -> Unit) {
    val announce = if (streakOnly) stringResource(R.string.announce_done)
                   else stringResource(R.string.announce_added, pending.amount.grouped())
    Row(
        Modifier.fillMaxWidth().card().semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announce
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (streakOnly) stringResource(R.string.marked_done) else stringResource(R.string.added, pending.amount.grouped()),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = undo) { Text(stringResource(R.string.undo), fontWeight = FontWeight.Bold) }
    }
}

/** The large +mala button: the one big control on the screen. */
@Composable
private fun BigButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, modifier: Modifier = Modifier,
                      enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Space.bigButton),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = Theme.colors.accent, contentColor = Theme.colors.onAccent),
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.padding(end = Space.s)) }
        Text(title, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            fontFamily = MaterialTheme.typography.displayMedium.fontFamily, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AmountDialog(onDismiss: () -> Unit, onAdd: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val amount = text.filter { it in '0'..'9' }.take(9).toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_amount)) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.amount)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { amount?.let(onAdd) }, enabled = amount != null) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Counted for Sunday · you started around 23:30", with the one-tap switch. */
@Composable
fun AfterMidnightDialog(prompt: AfterMidnightPrompt, model: AppModel) {
    val zone = prompt.session.zone
    val counted = prompt.sheet.countedFor.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val other = prompt.sheet.alternative.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    AlertDialog(
        onDismissRequest = { model.dismissAfterMidnight() },
        title = { Text(stringResource(R.string.counted_for, counted)) },
        text = { Text(stringResource(R.string.started_around, prompt.sheet.startedAround.shortTime(zone))) },
        confirmButton = { TextButton(onClick = { model.dismissAfterMidnight() }) { Text(stringResource(R.string.keep_day, counted)) } },
        dismissButton = { TextButton(onClick = { model.choose(prompt.sheet.alternative, prompt) }) { Text(stringResource(R.string.count_instead, other)) } },
    )
}

/** "23:30" or "11:30 PM", as the locale prefers. */
private fun Instant.shortTime(zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(atZone(zone))
