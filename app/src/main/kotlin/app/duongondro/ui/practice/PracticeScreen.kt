package app.duongondro.ui.practice

import android.text.format.DateFormat
import app.duongondro.ui.shownName
import app.duongondro.ui.shownSecondName
import androidx.compose.ui.platform.LocalConfiguration
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import app.duongondro.data.Covers
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.core.PendingLog
import app.duongondro.core.Session
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.model.AfterMidnightPrompt
import app.duongondro.model.AppModel
import app.duongondro.ui.Bar
import app.duongondro.ui.CardSection
import app.duongondro.ui.FilledAction
import app.duongondro.ui.ListRow
import app.duongondro.ui.OutlinedAction
import app.duongondro.ui.RowDivider
import app.duongondro.ui.SoftAction
import app.duongondro.ui.grouped
import app.duongondro.ui.theme.Radius
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A practice's own screen: the only place counts are logged. Each +mala opens
 * a few seconds' Undo; the session is written only when that window closes.
 * The +mala button owns the bottom edge, with the Undo strip above it, so the
 * button never moves under the thumb mid-count.
 */
@Composable
fun PracticeScreen(model: AppModel, practiceId: String, back: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val pending by model.pending.collectAsStateWithLifecycle()
    val now by model.now.collectAsStateWithLifecycle()
    val practice = snapshot.practices.firstOrNull { it.id == practiceId }
    val view = LocalView.current
    var askAmount by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var coverVersion by remember { mutableIntStateOf(0) }
    val own = remember(practiceId, coverVersion) { Covers.photo(context, practiceId)?.asImageBitmap() }
    val builtIn = Covers.builtIn(practiceId)
    val hasCover = own != null || builtIn != null

    Column(Modifier.fillMaxSize().background(Theme.colors.ground)) {
        // With a cover the photo runs up under the status bar and the back button floats over it.
        if (!hasCover) BackButton(back, Modifier.statusBarsPadding().padding(Space.xs))
        if (practice == null) {
            Text(stringResource(R.string.practice_gone), color = Theme.colors.muted, modifier = Modifier.padding(Space.xl))
            return
        }
        val tap = { amount: Int ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            model.add(amount, practice.id)
        }
        val streak = model.streak(practice.id, now)
        val mine = pending?.takeIf { it.practiceId == practice.id }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (hasCover) CoverHeader(practice.id, own, builtIn) { coverVersion++ }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(top = if (hasCover) Space.xl else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.xl),
                ) {
                    Header(practice, snapshot.sessionsOf(practice.id)) {
                        if (!hasCover) AddCoverButton(practice.id) { coverVersion++ }
                    }
                    if (practice.streakOnly) {
                        StreakOnlyStatus(streak.current, streak.longest, model.practisedToday(practice.id, now))
                    } else {
                        CountBlock(practice, snapshot.sessionsOf(practice.id), model.malaSize(practice), streak.current, now)
                    }
                }
            }
            if (hasCover) BackButton(back, Modifier.statusBarsPadding().padding(Space.xs).background(Theme.colors.coverButton, CircleShape))
        }
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            mine?.let { UndoStrip(it, practice.streakOnly) { model.undo() } }
            if (practice.streakOnly) {
                val done = model.practisedToday(practice.id, now)
                BigButton(stringResource(if (done) R.string.done_today else R.string.mark_today_done), Theme.type.bigButtonLabel,
                    icon = true, enabled = !done && mine == null) { tap(0) }
            } else {
                val mala = model.malaSize(practice)
                val label = stringResource(R.string.add_one_mala, mala)
                BigButton("+$mala", Theme.type.bigButton, modifier = Modifier.semantics { contentDescription = label }) { tap(mala) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                if (!practice.streakOnly) OutlinedAction(stringResource(R.string.custom), Modifier.weight(1f), height = Size.secondary) { askAmount = true }
                SoftAction(stringResource(R.string.history), Modifier.weight(1f)) { showHistory = true }
            }
        }
    }
    if (askAmount && practice != null) {
        AmountDialog(onDismiss = { askAmount = false }) { amount ->
            askAmount = false
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            model.add(amount, practice.id)
        }
    }
    if (showHistory && practice != null) {
        HistorySheet(practice, snapshot.sessionsOf(practice.id)) { showHistory = false }
    }
}

/** "Dorje Sempa", and below it "Diamond Mind · round 1 · 43,308 lifetime". */
@Composable
private fun Header(p: TrackedPractice, sessions: List<Session>, extra: @Composable () -> Unit = {}) {
    val rounds = if (p.streakOnly) null else p.rounds(sessions)
    val parts = buildList {
        p.practice.shownSecondName()?.let(::add)
        if (p.streakOnly) add(stringResource(R.string.header_streak_only))
        else {
            rounds?.let { add(stringResource(R.string.header_round, it.round)) }
            add(stringResource(R.string.header_lifetime, p.lifetime(sessions).grouped()))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(p.practice.shownName(), style = Theme.type.pageTitle)
        Text(parts.joinToString(" · "), style = Theme.type.subtitle, color = Theme.colors.muted)
        extra()
    }
}

/** The count in the current round, its target, a bar, today's total and the streak. */
@Composable
private fun CountBlock(p: TrackedPractice, sessions: List<Session>, mala: Int, streak: Int, now: Instant) {
    val rounds = p.rounds(sessions)
    val target = p.practice.target
    val shown = rounds?.inRound ?: p.lifetime(sessions)
    val today = civilDate(now, ZoneId.systemDefault())
    val todayTotal = sessions.filter { it.day == today }.sumOf { it.amount }
    Column(verticalArrangement = Arrangement.spacedBy(Space.m - Space.xxs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
            Text(shown.grouped(), style = Theme.type.count, color = Theme.colors.accent, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (target != null) {
                Text(stringResource(R.string.of_target, target.grouped()), style = Theme.type.body, color = Theme.colors.muted,
                    modifier = Modifier.padding(bottom = Space.s))
            }
        }
        if (rounds != null && target != null) Bar(rounds.inRound.toFloat() / target, Size.barThick)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (todayTotal >= mala) pluralStringResource(R.plurals.today_malas, todayTotal / mala, todayTotal.grouped(), todayTotal / mala)
                else stringResource(R.string.today_amount, todayTotal.grouped()),
                style = Theme.type.secondary, color = Theme.colors.muted,
            )
            StreakBadge(streak)
        }
    }
}

@Composable
private fun StreakBadge(days: Int) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.flame, modifier = Modifier.size(Space.l))
        Text(pluralStringResource(R.plurals.days, days, days), style = Theme.type.secondary.copy(fontWeight = FontWeight.Bold), color = Theme.colors.ink)
    }
}

@Composable
private fun StreakOnlyStatus(current: Int, longest: Int, done: Boolean) {
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.flame, modifier = Modifier.size(Size.heroFlame))
            Text(pluralStringResource(R.plurals.days, current, current), style = Theme.type.count, color = Theme.colors.accent, maxLines = 1)
        }
        Text(stringResource(if (done) R.string.done_today else R.string.not_yet_today), style = Theme.type.secondary, color = Theme.colors.muted)
        if (longest > current) Text(stringResource(R.string.longest_n, longest), style = Theme.type.secondary, color = Theme.colors.muted)
    }
}

/** "Added 108 · Undo" on an inverted strip, with a ring that empties as the window closes. */
@Composable
private fun UndoStrip(pending: PendingLog, streakOnly: Boolean, undo: () -> Unit) {
    val announce = if (streakOnly) stringResource(R.string.announce_done)
                   else stringResource(R.string.announce_added, pending.amount.grouped())
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.toastHeight)
            .background(Theme.colors.toast, MaterialTheme.shapes.medium)
            .padding(start = Space.l, end = Space.s)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announce
            },
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountdownRing(pending.deadline)
        Text(
            if (streakOnly) stringResource(R.string.marked_done) else stringResource(R.string.added, pending.amount.grouped()),
            Modifier.weight(1f), style = Theme.type.secondary.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.toastInk,
        )
        TextButton(onClick = undo, colors = ButtonDefaults.textButtonColors(contentColor = Theme.colors.toastInk),
            shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.undo), style = Theme.type.secondary.copy(fontWeight = FontWeight.ExtraBold, textDecoration = TextDecoration.Underline))
        }
    }
}

@Composable
private fun CountdownRing(deadline: Instant) {
    val total = PendingLog.WINDOW.toMillis().toFloat()
    val left by produceState(1f, deadline) {
        while (true) {
            val ms = Duration.between(Instant.now(), deadline).toMillis()
            value = (ms / total).coerceIn(0f, 1f)
            if (ms <= 0) break
            delay(50)
        }
    }
    val track = Theme.colors.toastTrack
    val ink = Theme.colors.toastInk
    Canvas(Modifier.size(Size.ring)) {
        val stroke = Size.ringStroke.toPx()
        val inset = stroke / 2
        val arc = GeoSize(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
        drawArc(ink, -90f, 360f * left, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** The large +mala button: 88 dp, 8 dp corners, the one big control on the screen. */
@Composable
private fun BigButton(title: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, icon: Boolean = false,
                      enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Size.bigButton),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Radius.bigButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = Theme.colors.accent, contentColor = Theme.colors.onAccent,
            disabledContainerColor = Theme.colors.accent.copy(alpha = 0.4f), disabledContentColor = Theme.colors.onAccent.copy(alpha = 0.7f),
        ),
    ) {
        if (icon) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(end = Space.s))
        Text(title, style = style, maxLines = 1)
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

/** Every session of one practice, newest day first, with each day's total. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(p: TrackedPractice, sessions: List<Session>, dismiss: () -> Unit) {
    val days = sessions.groupBy { it.day }.toSortedMap(compareByDescending { it })
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Theme.colors.ground) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.xl), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.history), Modifier.weight(1f), style = Theme.type.sheetTitle)
            TextButton(onClick = dismiss) { Text(stringResource(R.string.done)) }
        }
        if (days.isEmpty()) {
            Text(stringResource(R.string.history_empty), color = Theme.colors.muted, modifier = Modifier.padding(Space.xl))
        }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l)) {
            items(days.entries.toList(), key = { it.key.toString() }) { (day, list) ->
                CardSection(header = day.formatDay()) {
                    val ordered = list.sortedByDescending { it.startedAt }
                    ordered.forEachIndexed { i, s ->
                        if (i > 0) RowDivider()
                        ListRow(s.startedAt.shortTime(s.zone), trailing = {
                            Text(if (p.streakOnly) stringResource(R.string.done_session) else s.amount.grouped(),
                                style = Theme.type.body.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.ink)
                        }, titleColor = Theme.colors.muted)
                    }
                    if (!p.streakOnly && list.size > 1) {
                        RowDivider()
                        ListRow(stringResource(R.string.total), titleColor = Theme.colors.ink, trailing = {
                            Text(list.sumOf { it.amount }.grouped(), style = Theme.type.body.copy(fontWeight = FontWeight.Bold), color = Theme.colors.ink)
                        })
                    }
                }
            }
        }
    }
}

/** "Sunday 4 October", in the locale's order. */
private fun LocalDate.formatDay(): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(this)
}

/** "Sunday 4": the weekday and day of the month, for the after-midnight choice. */
private fun LocalDate.weekdayAndDay(): String {
    return "${dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())} $dayOfMonth"
}

/**
 * The after-midnight sheet: "Logged 108", which day it counted for, and a
 * two-way choice. Done applies the choice; dismissing keeps what was counted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfterMidnightDialog(prompt: AfterMidnightPrompt, model: AppModel) {
    val sheet = prompt.sheet
    val zone = prompt.session.zone
    val days = listOf(sheet.countedFor, sheet.alternative).sorted()
    var picked by remember(prompt) { mutableStateOf(sheet.countedFor) }
    val counted = sheet.countedFor.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, LocalConfiguration.current.locales[0])
    val time = prompt.session.loggedAt.shortTime(zone)
    val body = stringResource(R.string.after_midnight_body, counted, sheet.startedAround.shortTime(zone))
    val bolded = buildAnnotatedString {
        val at = body.indexOf(counted)
        if (at < 0) append(body) else {
            append(body.substring(0, at))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Theme.colors.ink)) { append(counted) }
            append(body.substring(at + counted.length))
        }
    }
    ModalBottomSheet(onDismissRequest = { model.dismissAfterMidnight() }, containerColor = Theme.colors.card) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m + Space.xxs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(
                    if (prompt.session.amount > 0) stringResource(R.string.logged_amount, prompt.session.amount.grouped())
                    else stringResource(R.string.marked_done),
                    style = Theme.type.sheetTitle,
                )
                Text(time, style = Theme.type.secondary.copy(fontWeight = FontWeight.SemiBold), color = Theme.colors.muted)
            }
            Text(bolded, style = Theme.type.lead, color = Theme.colors.soft)
            Choice(days.map { it to it.weekdayAndDay() }, picked, stringResource(R.string.count_for)) { picked = it }
            Text(stringResource(R.string.after_midnight_hint), style = Theme.type.footnote, color = Theme.colors.muted)
            SoftAction(stringResource(R.string.done)) {
                if (picked == sheet.countedFor) model.dismissAfterMidnight() else model.choose(picked, prompt)
            }
        }
    }
}

/** A two-way choice on a soft track, the selected side filled. */
@Composable
private fun Choice(options: List<Pair<LocalDate, String>>, selected: LocalDate, description: String, pick: (LocalDate) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Theme.colors.softFill, MaterialTheme.shapes.medium).padding(Space.xs)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        options.forEach { (day, label) ->
            val on = day == selected
            Button(
                onClick = { pick(day) }, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f).heightIn(min = Size.field),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) Theme.colors.accent else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (on) Theme.colors.onAccent else Theme.colors.soft,
                ),
            ) { Text(label, style = Theme.type.body.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold), maxLines = 1) }
        }
    }
}

/** "23:30" or "11:30 PM", as the locale prefers. */
private fun Instant.shortTime(zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(atZone(zone))

@Composable
private fun BackButton(back: () -> Unit, modifier: Modifier) {
    IconButton(onClick = back, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}
