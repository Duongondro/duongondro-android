package app.duongondro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.duongondro.R

/**
 * All colours, shapes and type (design: Look). Karma Kagyu burgundy with gold;
 * dark mode is first-class, on warm near-blacks. No literals in composables.
 */
@Immutable
data class DuongondroColors(
    val accent: Color,
    val onAccent: Color,
    val gold: Color,
    val ground: Color,
    val card: Color,
    val cardBorder: Color,
    val muted: Color,
    val streakCard: Color,
    /** Streak flames are gold, never orange. */
    val flame: Color,
    /** Streak numbers as text: gold only reads on dark, so burgundy in light mode. */
    val flameText: Color,
    val destructive: Color,
    /** Welcome: warm off-white with a burgundy button, or near-black burgundy with a gold one. */
    val welcomeGround: Color,
    val welcomePrimary: Color,
)

private val Light = DuongondroColors(
    accent = Color(0xFF7A1F2E), onAccent = Color(0xFFFFFFFF), gold = Color(0xFFD4A72C),
    ground = Color(0xFFF7F3F1), card = Color(0xFFFFFFFF), cardBorder = Color(0xFFEDE4E6),
    muted = Color(0xFF6B5A60), streakCard = Color(0xFFF6E9EB), flame = Color(0xFFC9952B), flameText = Color(0xFF7A1F2E),
    destructive = Color(0xFFB3261E), welcomeGround = Color(0xFFF7F3F1), welcomePrimary = Color(0xFF7A1F2E),
)

private val Dark = DuongondroColors(
    accent = Color(0xFFE8909C), onAccent = Color(0xFF120A0C), gold = Color(0xFFE3B341),
    ground = Color(0xFF120A0C), card = Color(0xFF2B1C20), cardBorder = Color(0xFF46323A),
    muted = Color(0xFFC2B2B7), streakCard = Color(0xFF4A1C27), flame = Color(0xFFE3B341), flameText = Color(0xFFE3B341),
    destructive = Color(0xFFF2827A), welcomeGround = Color(0xFF1E0C11), welcomePrimary = Color(0xFFE3B341),
)

/** Spacing steps and fixed sizes, so composables carry no layout literals. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 32.dp
    val bigButton = 88.dp
    val button = 50.dp
}

/** Material 3 with a custom shape scale: 4 dp small components, 6 dp buttons, cards and the FAB. */
object Radius {
    val small = 4.dp
    val card = 6.dp
    /** Dialogs and sheets, as iOS sheets (12 pt). */
    val sheet = 12.dp
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.small),
    small = RoundedCornerShape(Radius.small),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.sheet),
)

/** IBM Plex Sans SemiBold/Bold for headings and big numbers; the system font for prose. */
private val plex = FontFamily(
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
)

private val typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = plex, fontWeight = FontWeight.Bold),
        displayMedium = displayMedium.copy(fontFamily = plex, fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontFamily = plex, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = plex, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = plex, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = plex, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = plex, fontWeight = FontWeight.SemiBold),
    )
}

val LocalColors = staticCompositionLocalOf { Light }

object Theme {
    val colors: DuongondroColors @Composable get() = LocalColors.current
}

@Composable
fun DuongondroTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, secondary = c.gold, onSecondary = c.onAccent,
            background = c.ground, onBackground = Color(0xFFF4ECEE), surface = c.ground, onSurface = Color(0xFFF4ECEE),
            surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerLow = c.card,
            onSurfaceVariant = c.muted, outline = c.cardBorder, outlineVariant = c.cardBorder, error = c.destructive,
            secondaryContainer = c.streakCard, onSecondaryContainer = c.accent, surfaceVariant = c.cardBorder,
            surfaceContainerHighest = c.cardBorder,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, secondary = c.gold, onSecondary = Color(0xFF2A1A00),
            background = c.ground, onBackground = Color(0xFF1F1416), surface = c.ground, onSurface = Color(0xFF1F1416),
            surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerLow = c.card,
            onSurfaceVariant = c.muted, outline = c.cardBorder, outlineVariant = c.cardBorder, error = c.destructive,
            secondaryContainer = c.streakCard, onSecondaryContainer = c.accent, surfaceVariant = c.cardBorder,
            surfaceContainerHighest = c.cardBorder,
        )
    }
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
    }
}
