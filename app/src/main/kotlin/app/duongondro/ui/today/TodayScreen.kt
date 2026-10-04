package app.duongondro.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.core.Streak
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.model.AppModel
import app.duongondro.model.Snapshot
import app.duongondro.ui.PracticeName
import app.duongondro.ui.card
import app.duongondro.ui.grouped
import app.duongondro.ui.theme.Radius
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Today: the headline streak and the daily practices. No logging here, so a
 * stray tap while scrolling never adds a mala to the wrong practice.
 */
@Composable
fun TodayScreen(model: AppModel, open: (String) -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val now by model.now.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize().background(Theme.colors.ground),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.xl, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        item {
            Text(stringResource(R.string.tab_today), style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(vertical = Space.s))
        }
        item { HeadlineCard(model.headline(now)) }
        items(snapshot.activePractices, key = { it.id }) { p ->
            PracticeRow(p, snapshot, model.streak(p.id, now), model.practisedToday(p.id, now)) { open(p.id) }
        }
    }
}

@Composable
private fun HeadlineCard(result: Streak.Result) {
    Row(
        Modifier.fillMaxWidth().background(Theme.colors.streakCard, RoundedCornerShape(Radius.card)).padding(Space.l)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.flame,
            modifier = Modifier.size(Space.xxl))
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(pluralStringResource(R.plurals.days, result.current, result.current), style = MaterialTheme.typography.headlineLarge)
            val deadline = result.deadline
            Text(
                if (result.current > 0 && deadline != null) {
                    val day = civilDate(deadline.minusSeconds(1), ZoneId.systemDefault())
                    stringResource(R.string.practise_before_end, day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                } else stringResource(R.string.any_practice_starts),
                style = MaterialTheme.typography.bodyMedium, color = Theme.colors.muted,
            )
        }
    }
}

@Composable
private fun PracticeRow(p: TrackedPractice, snapshot: Snapshot, streak: Streak.Result, done: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).card(),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val doneLabel = stringResource(if (done) R.string.done_today else R.string.not_yet_today)
        if (done) {
            Icon(Icons.Filled.CheckCircle, contentDescription = doneLabel, tint = Theme.colors.accent)
        } else {
            Icon(painterResource(R.drawable.ic_circle), contentDescription = doneLabel, tint = Theme.colors.muted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            PracticeName(p.practice)
            ProgressLine(p, snapshot)
        }
        if (streak.current > 0) {
            val label = pluralStringResource(R.plurals.days, streak.current, streak.current)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = label }) {
                Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.flame)
                Text("${streak.current}", style = MaterialTheme.typography.titleMedium, color = Theme.colors.flame)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
    }
}

/** "Round 5 · 35,556 of 111,111", a lifetime total, or "Streak only". */
@Composable
fun ProgressLine(p: TrackedPractice, snapshot: Snapshot) {
    val sessions = snapshot.sessionsOf(p.id)
    val rounds = p.rounds(sessions)
    val target = p.practice.target
    Text(
        when {
            p.streakOnly -> stringResource(R.string.streak_only)
            rounds != null && target != null -> stringResource(R.string.round_progress, rounds.round, rounds.inRound.grouped(), target.grouped())
            else -> stringResource(R.string.in_total, p.lifetime(sessions).grouped())
        },
        style = MaterialTheme.typography.bodySmall, color = Theme.colors.muted,
    )
}
