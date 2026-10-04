package app.duongondro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import app.duongondro.core.Practice
import app.duongondro.ui.theme.Radius
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import java.text.NumberFormat

/** Card fill with the hairline border that keeps dark cards off the ground. */
@Composable
fun Modifier.card(): Modifier {
    val shape = RoundedCornerShape(Radius.card)
    return this
        .background(Theme.colors.card, shape)
        .border(BorderStroke(Space.xxs / 2, Theme.colors.cardBorder), shape)
        .padding(Space.l)
}

/**
 * The leading name and, below it, the English second line. Wraps rather than
 * truncates: German and Hungarian run long.
 */
@Composable
fun PracticeName(practice: Practice, large: Boolean = false, modifier: Modifier = Modifier) {
    val align = if (large) TextAlign.Center else TextAlign.Start
    Column(
        modifier,
        horizontalAlignment = if (large) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        Text(practice.name, style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, textAlign = align)
        practice.secondName?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Theme.colors.muted, textAlign = align)
        }
    }
}

/** Locale grouping: 43,308 · 43.308 · 43 308. */
fun Int.grouped(): String = NumberFormat.getIntegerInstance().format(this)

/** The mockups' flat buttons: filled for the main action, outlined beside it, soft-filled for the least. */
@Composable
fun FilledAction(
    text: String, modifier: Modifier = Modifier, fill: Color = Theme.colors.accent, ink: Color = Theme.colors.onAccent,
    height: Dp = Size.button, enabled: Boolean = true, onClick: () -> Unit,
) {
    Button(
        onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = fill, contentColor = ink,
            disabledContainerColor = fill.copy(alpha = DISABLED), disabledContentColor = ink.copy(alpha = DISABLED),
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = height),
    ) { Text(text, style = Theme.type.button) }
}

@Composable
fun OutlinedAction(
    text: String, modifier: Modifier = Modifier, tint: Color = Theme.colors.accent, height: Dp = Size.button,
    fillWidth: Boolean = true, border: Color = tint, container: Color = Theme.colors.card, onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = container, contentColor = tint),
        border = BorderStroke(Space.xxs, border),
        contentPadding = PaddingValues(horizontal = Space.s),
        modifier = (if (fillWidth) modifier.fillMaxWidth() else modifier).heightIn(min = height),
    ) { Text(text, style = Theme.type.button.copy(fontSize = Theme.type.body.fontSize), textAlign = TextAlign.Center, maxLines = 1) }
}

@Composable
fun SoftAction(text: String, modifier: Modifier = Modifier, height: Dp = Size.secondary, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Theme.colors.softFill, contentColor = Theme.colors.ink),
        modifier = modifier.fillMaxWidth().heightIn(min = height),
    ) { Text(text, style = Theme.type.button.copy(fontSize = Theme.type.body.fontSize)) }
}

/** A progress bar in the accent colour on its track. */
@Composable
fun Bar(fraction: Float, height: Dp = Size.barThin, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Theme.colors.track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Theme.colors.accent))
    }
}

/** Grouped rows on a white card: an uppercase header, hairlines between rows, an optional footnote. */
@Composable
fun CardSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        header?.let {
            Text(it.uppercase(), style = Theme.type.caps, color = Theme.colors.muted, modifier = Modifier.padding(horizontal = Space.l))
        }
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(Theme.colors.card), content = content)
        footer?.let {
            Text(it, style = Theme.type.footnote, color = Theme.colors.muted, modifier = Modifier.padding(horizontal = Space.l))
        }
    }
}

/** Hairline between the rows of a [CardSection]. */
@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = Space.l), color = Theme.colors.line)
}

/** A row of a [CardSection]: label, optional muted value, optional chevron. */
@Composable
fun ListRow(
    title: String, modifier: Modifier = Modifier, detail: String? = null, chevron: Boolean = false,
    titleColor: Color = Theme.colors.ink, detailColor: Color = Theme.colors.muted, semibold: Boolean = false, onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .heightIn(min = Size.minTap).padding(horizontal = Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).padding(vertical = Space.s), style = if (semibold) Theme.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else Theme.type.body, color = titleColor)
        detail?.let { Text(it, style = Theme.type.body, color = detailColor) }
        trailing?.invoke()
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Theme.colors.muted)
    }
}

private const val DISABLED = 0.4f

/** The time picker in the app's colours rather than Material's lavender. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun timePickerColors() = androidx.compose.material3.TimePickerDefaults.colors(
    clockDialColor = Theme.colors.softFill,
    selectorColor = Theme.colors.accent,
    containerColor = Theme.colors.ground,
    periodSelectorBorderColor = Theme.colors.inputBorder,
    clockDialSelectedContentColor = Theme.colors.onAccent,
    clockDialUnselectedContentColor = Theme.colors.ink,
    periodSelectorSelectedContainerColor = Theme.colors.streakCard,
    periodSelectorUnselectedContainerColor = Color.Transparent,
    periodSelectorSelectedContentColor = Theme.colors.accent,
    periodSelectorUnselectedContentColor = Theme.colors.soft,
    timeSelectorSelectedContainerColor = Theme.colors.streakCard,
    timeSelectorUnselectedContainerColor = Theme.colors.softFill,
    timeSelectorSelectedContentColor = Theme.colors.accent,
    timeSelectorUnselectedContentColor = Theme.colors.ink,
)
