package app.duongondro.ui.today

import android.text.format.DateFormat
import app.duongondro.ui.shownName
import app.duongondro.ui.shownSecondName
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.core.Streak
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.model.AppModel
import app.duongondro.model.Snapshot
import app.duongondro.ui.Bar
import app.duongondro.ui.grouped
import app.duongondro.ui.theme.Radius
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today: the date and title, the headline streak on a burgundy card, then the
 * daily practices as cards. No logging here, so a stray tap while scrolling
 * never adds a mala to the wrong practice.
 */
@Composable
fun TodayScreen(model: AppModel, open: (String) -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val now by model.now.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize().background(Theme.colors.ground),
        contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.l),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(dateLine(now), style = Theme.type.secondary.copy(fontWeight = FontWeight.SemiBold),
                    color = Theme.colors.muted)
                Text(stringResource(R.string.tab_today), style = Theme.type.largeTitle)
            }
        }
        item { HeadlineCard(model.headline(now), Modifier.padding(top = Space.m)) }
        item {
            Text(stringResource(R.string.daily_practices).uppercase(), style = Theme.type.caps, color = Theme.colors.muted,
                modifier = Modifier.padding(top = Space.xl, bottom = Space.s))
        }
        items(snapshot.activePractices, key = { it.id }) { p ->
            PracticeCard(p, snapshot, model.streak(p.id, now), model.practisedToday(p.id, now),
                Modifier.padding(bottom = Space.m - Space.xxs)) { open(p.id) }
        }
    }
}

/** "Sunday, 4 October", in the locale's order. */
private fun dateLine(now: java.time.Instant): String {
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
    return DateTimeFormatter.ofPattern(pattern, locale).format(civilDate(now, zone))
}

@Composable
private fun HeadlineCard(result: Streak.Result, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Theme.colors.hero, RoundedCornerShape(Radius.card))
            .padding(horizontal = Space.l + Space.xxs, vertical = Space.l).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.m + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.gold, modifier = Modifier.size(Size.heroFlame))
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(pluralStringResource(R.plurals.days, result.current, result.current), style = Theme.type.hero, color = Theme.colors.heroInk)
            Text(
                if (result.current > 0) stringResource(R.string.streak_longest, result.longest)
                else stringResource(R.string.any_practice_starts),
                style = Theme.type.secondary.copy(fontWeight = FontWeight.Medium),
                color = Theme.colors.heroInk.copy(alpha = 0.9f),
            )
        }
    }
}

/**
 * One daily practice: the name with its streak, a thin bar for counted
 * practices, and a line saying where it stands. A streak-only practice shows a
 * filled check once done today.
 */
@Composable
private fun PracticeCard(
    p: TrackedPractice, snapshot: Snapshot, streak: Streak.Result, done: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val sessions = snapshot.sessionsOf(p.id)
    val rounds = if (p.streakOnly) null else p.rounds(sessions)
    val target = p.practice.target
    val shape = RoundedCornerShape(Radius.card)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Theme.colors.card, shape).clickable(onClick = onClick)
            .padding(start = Space.l, top = Space.m + Space.xxs, bottom = Space.m + Space.xxs, end = Space.m + Space.xxs),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text(p.practice.shownName(), style = Theme.type.cardTitle, color = Theme.colors.ink, modifier = Modifier.weight(1f, fill = false))
                if (streak.current > 0) {
                    val label = pluralStringResource(R.plurals.days, streak.current, streak.current)
                    Row(Modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
                        Icon(painterResource(R.drawable.ic_flame), contentDescription = null, tint = Theme.colors.flame,
                            modifier = Modifier.size(Size.cardFlame))
                        Text("${streak.current}", style = Theme.type.secondary.copy(fontWeight = FontWeight.Bold),
                            color = Theme.colors.ink)
                    }
                }
            }
            if (rounds != null && target != null) Bar(rounds.inRound.toFloat() / target)
            Text(statusLine(p, snapshot, done), style = Theme.type.footnote, color = Theme.colors.muted)
        }
        if (p.streakOnly && done) {
            val label = stringResource(R.string.done_today)
            Box(Modifier.size(Size.checkBadge).background(Theme.colors.accent, CircleShape).semantics { contentDescription = label },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Theme.colors.onAccent, modifier = Modifier.size(Space.l))
            }
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
        }
    }
}

/** "Diamond Mind · 43,308 of 111,111", "12,960 of 111,111 · not yet today", "Loving Eyes · done today". */
@Composable
private fun statusLine(p: TrackedPractice, snapshot: Snapshot, done: Boolean): String {
    val sessions = snapshot.sessionsOf(p.id)
    val rounds = if (p.streakOnly) null else p.rounds(sessions)
    val target = p.practice.target
    val parts = buildList {
        p.practice.shownSecondName()?.let(::add)
        if (rounds != null && target != null) {
            add(
                if (rounds.round > 1) stringResource(R.string.status_round, rounds.round, rounds.inRound.grouped(), target.grouped())
                else stringResource(R.string.status_progress, rounds.inRound.grouped(), target.grouped())
            )
        } else if (!p.streakOnly) {
            add(stringResource(R.string.status_total, p.lifetime(sessions).grouped()))
        }
        if (p.streakOnly || !done) add(stringResource(if (done) R.string.status_done else R.string.status_not_yet))
    }
    return parts.joinToString(" · ")
}
