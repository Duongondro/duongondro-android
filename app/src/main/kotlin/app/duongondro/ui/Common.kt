package app.duongondro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.duongondro.core.Practice
import app.duongondro.ui.theme.Radius
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme
import androidx.compose.foundation.shape.RoundedCornerShape
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
