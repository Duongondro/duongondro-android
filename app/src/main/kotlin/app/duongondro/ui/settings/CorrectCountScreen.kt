package app.duongondro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duongondro.R
import app.duongondro.core.Session
import app.duongondro.core.TrackedPractice
import app.duongondro.model.AppModel
import app.duongondro.ui.Bar
import app.duongondro.ui.CardSection
import app.duongondro.ui.FilledAction
import app.duongondro.ui.ListRow
import app.duongondro.ui.RowDivider
import app.duongondro.ui.TextAction
import app.duongondro.ui.grouped
import app.duongondro.ui.shownName
import app.duongondro.ui.signed
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme

/**
 * A practice's settings, Count (design decision 34): the total, its round, and
 * the way into [CorrectCountScreen].
 */
@Composable
internal fun CountSection(p: TrackedPractice, sessions: List<Session>, correct: () -> Unit) {
    val lifetime = p.lifetime(sessions)
    val rounds = p.rounds(sessions)
    val target = p.practice.target
    CardSection(stringResource(R.string.count_section)) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
                Text((rounds?.inRound ?: lifetime).grouped(), style = Theme.type.largeTitle, color = Theme.colors.ink, maxLines = 1)
                if (rounds != null && target != null) {
                    Text(stringResource(R.string.count_of_round, target.grouped(), rounds.round),
                        style = Theme.type.secondary, color = Theme.colors.muted, modifier = Modifier.padding(bottom = Space.xs))
                }
            }
            if (rounds != null && target != null) Bar(rounds.inRound.toFloat() / target)
        }
        RowDivider()
        ListRow(stringResource(R.string.correct_the_count), titleColor = Theme.colors.accent, semibold = true, chevron = true, onClick = correct)
    }
}

/**
 * Correct the count (design decision 34): the person types the count as it is
 * now, by round and count in the round for a practice with a target. The
 * change is saved as a correction session; no lower limit.
 */
@Composable
fun CorrectCountScreen(model: AppModel, practiceId: String, back: () -> Unit) {
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val p = snapshot.practices.firstOrNull { it.id == practiceId } ?: return
    val sessions = snapshot.sessionsOf(practiceId)
    val was = p.lifetime(sessions)
    val target = p.practice.target?.takeIf { it > 0 }
    val start = p.rounds(sessions)
    var round by rememberSaveable { mutableStateOf((start?.round ?: 1).toString()) }
    var count by rememberSaveable { mutableStateOf((start?.inRound ?: was).toString()) }
    val r = round.toIntOrNull()?.takeIf { it >= 1 }
    val c = count.toLongOrNull()
    val now: Int? = when {
        c == null -> null
        target == null -> c.takeIf { it <= Int.MAX_VALUE }?.toInt()
        r == null -> null
        else -> ((r - 1).toLong() * target + c).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
    val change = now?.let { it - was }
    Page(stringResource(R.string.correct_the_count), back) {
        Text(p.practice.shownName(), style = Theme.type.body, color = Theme.colors.soft, modifier = Modifier.padding(horizontal = Space.xs))
        CardSection(footer = stringResource(R.string.correction_footer)) {
            Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    if (target != null) {
                        OutlinedTextField(round, { round = it.filter(Char::isDigit).take(4) },
                            label = { Text(stringResource(R.string.round_label)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = Theme.type.field,
                            modifier = Modifier.width(Size.roundField))
                    }
                    OutlinedTextField(count, { count = it.filter(Char::isDigit).take(9) },
                        label = { Text(stringResource(if (target != null) R.string.count_in_round else R.string.count_now)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), textStyle = Theme.type.field,
                        modifier = Modifier.weight(1f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.correction_was), style = Theme.type.secondary, color = Theme.colors.soft)
                        Text(was.grouped(), style = Theme.type.secondary, color = Theme.colors.soft)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.correction_change), style = Theme.type.secondary.copy(fontWeight = FontWeight.Bold), color = Theme.colors.ink)
                        Text(change?.signed() ?: "–", style = Theme.type.secondary.copy(fontWeight = FontWeight.Bold), color = Theme.colors.ink)
                    }
                }
            }
        }
        FilledAction(stringResource(R.string.save_count, (now ?: was).grouped()), enabled = now != null && change != 0) {
            now?.let { model.correctCount(practiceId, it) }
            back()
        }
        TextAction(stringResource(R.string.cancel), onClick = back)
    }
}
