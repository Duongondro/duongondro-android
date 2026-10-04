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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
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
    /** Body text, and the darker secondary text of questions and forms. */
    val ink: Color,
    val soft: Color,
    /** Hairlines between rows. */
    val line: Color,
    /** Soft-filled buttons and segmented tracks. */
    val softFill: Color,
    /** Input borders and the onboarding dashes not yet reached. */
    val inputBorder: Color,
    /** Progress bars' track. */
    val track: Color,
    /** The headline streak card on Today: solid burgundy. */
    val hero: Color,
    val heroInk: Color,
    /** The Undo strip, inverted against the ground. */
    val toast: Color,
    val toastInk: Color,
    val toastTrack: Color,
    /** Streak flames are gold, never orange. */
    val flame: Color,
    /** Streak numbers as text: gold only reads on dark, so burgundy in light mode. */
    val flameText: Color,
    val destructive: Color,
    /** Welcome: warm off-white with a burgundy button, or near-black burgundy with a gold one. */
    val welcomeGround: Color,
    val welcomePrimary: Color,
    val welcomePrimaryInk: Color,
    val welcomeTitle: Color,
    val welcomeSoft: Color,
    /** The "end-to-end encrypted" line: a text-safe gold on either ground. */
    val welcomeGoldText: Color,
    val welcomeOutline: Color,
    val welcomeOutlineInk: Color,
)

private val Light = DuongondroColors(
    accent = Color(0xFF7A1F2E), onAccent = Color(0xFFFFFFFF), gold = Color(0xFFD4A72C),
    ground = Color(0xFFF7F3F1), card = Color(0xFFFFFFFF), cardBorder = Color(0xFFFFFFFF),
    muted = Color(0xFF6B5A60), streakCard = Color(0xFFF6E9EB), flame = Color(0xFFC9952B), flameText = Color(0xFF7A1F2E),
    destructive = Color(0xFFB3261E), welcomeGround = Color(0xFFF7F3F1), welcomePrimary = Color(0xFF7A1F2E),
    ink = Color(0xFF22151A), soft = Color(0xFF4E3F44), line = Color(0xFFEFE7E4), softFill = Color(0xFFEFE7E4),
    inputBorder = Color(0xFFDCCDD1), track = Color(0xFFF1E4E6), hero = Color(0xFF7A1F2E), heroInk = Color(0xFFFFFFFF),
    toast = Color(0xFF22151A), toastInk = Color(0xFFFFFFFF), toastTrack = Color(0xFF5A4A4F),
    welcomePrimaryInk = Color(0xFFFFFFFF), welcomeTitle = Color(0xFF7A1F2E), welcomeSoft = Color(0xFF4E3F44),
    welcomeGoldText = Color(0xFF7A5410), welcomeOutline = Color(0xFF7A1F2E), welcomeOutlineInk = Color(0xFF7A1F2E),
)

private val Dark = DuongondroColors(
    accent = Color(0xFFE8909C), onAccent = Color(0xFF120A0C), gold = Color(0xFFE3B341),
    ground = Color(0xFF120A0C), card = Color(0xFF2B1C20), cardBorder = Color(0xFF46323A),
    muted = Color(0xFFC2B2B7), streakCard = Color(0xFF4A1C27), flame = Color(0xFFE3B341), flameText = Color(0xFFE3B341),
    destructive = Color(0xFFF2827A), welcomeGround = Color(0xFF1E0C11), welcomePrimary = Color(0xFFD4A72C),
    ink = Color(0xFFF4ECEE), soft = Color(0xFFC2B2B7), line = Color(0xFF46323A), softFill = Color(0xFF3A2A2E),
    inputBorder = Color(0xFF46323A), track = Color(0xFF4A363B), hero = Color(0xFF4A1C27), heroInk = Color(0xFFF4ECEE),
    toast = Color(0xFFF4ECEE), toastInk = Color(0xFF22151A), toastTrack = Color(0xFFC9B9BD),
    welcomePrimaryInk = Color(0xFF2A1A06), welcomeTitle = Color(0xFFFFFFFF), welcomeSoft = Color(0xFFE9D7DB),
    welcomeGoldText = Color(0xFFE3B341), welcomeOutline = Color(0xFF6E4A54), welcomeOutlineInk = Color(0xFFFFFFFF),
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
    /** Between the onboarding step dashes. */
    val dash = 6.dp
}

/** Fixed control sizes. */
object Size {
    /** The +mala button's height. */
    val bigButton = 88.dp
    /** Welcome's door buttons and Continue. */
    val button = 56.dp
    val emblem = 220.dp
    /** The smallest tap target (Material's 48 dp). */
    val minTap = 48.dp
    /** Onboarding answers (Yes / Not yet). */
    val answer = 60.dp
    val field = 48.dp
    val secondary = 52.dp
    val aboutButton = 48.dp
    /** Progress bars: on Today's cards, and on the practice screen. */
    val barThin = 6.dp
    val barThick = 10.dp
    val stepDashWidth = 28.dp
    val stepDashHeight = 4.dp
    val checkBadge = 30.dp
    val heroFlame = 40.dp
    val cardFlame = 14.dp
    val sheetHandleWidth = 40.dp
    val sheetHandleHeight = 5.dp
    val toastHeight = 52.dp
    val ring = 22.dp
    val ringStroke = 3.dp
    val checkbox = 20.dp
}

/** Material 3 with a custom shape scale: 4 dp small components, 6 dp buttons, cards and the FAB. */
object Radius {
    val small = 4.dp
    val card = 6.dp
    val bigButton = 8.dp
    val dash = 2.dp
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

/** Text styles beyond the Material scale, from the mockups. Headings in Plex, prose in the system font. */
@Immutable
data class DuongondroType(
    val largeTitle: TextStyle,
    val welcomeTitle: TextStyle,
    val question: TextStyle,
    val header: TextStyle,
    val questionSmall: TextStyle,
    val pageTitle: TextStyle,
    val hero: TextStyle,
    val sheetTitle: TextStyle,
    val count: TextStyle,
    val bigButton: TextStyle,
    /** The streak-only "Mark today done" label, a sentence rather than a count. */
    val bigButtonLabel: TextStyle,
    val field: TextStyle,
    val cardTitle: TextStyle,
    val button: TextStyle,
    val caps: TextStyle,
    val body: TextStyle,
    val lead: TextStyle,
    /** The line under a screen title. */
    val subtitle: TextStyle,
    val secondary: TextStyle,
    val footnote: TextStyle,
    val mono: TextStyle,
)

private fun heading(size: Int, tracking: Double = 0.0, lineHeight: Double? = null) = TextStyle(
    fontFamily = plex, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = tracking.sp,
    lineHeight = (lineHeight?.times(size) ?: Double.NaN).let { if (it.isNaN()) TextUnit.Unspecified else it.sp },
)

private val type = DuongondroType(
    largeTitle = heading(34, -0.5),
    welcomeTitle = heading(44, -1.0),
    question = heading(36, lineHeight = 1.1),
    header = heading(32, lineHeight = 1.1),
    questionSmall = heading(28, lineHeight = 1.1),
    pageTitle = heading(30),
    hero = heading(28),
    sheetTitle = heading(26),
    count = heading(52, -1.0),
    bigButton = heading(40),
    bigButtonLabel = heading(22),
    field = heading(20),
    cardTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
    button = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
    caps = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
    body = TextStyle(fontSize = 16.sp),
    lead = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    subtitle = TextStyle(fontSize = 15.sp),
    secondary = TextStyle(fontSize = 14.sp),
    footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    mono = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace),
)

val LocalColors = staticCompositionLocalOf { Light }

object Theme {
    val colors: DuongondroColors @Composable get() = LocalColors.current
    val type: DuongondroType get() = app.duongondro.ui.theme.type
}

@Composable
fun DuongondroTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, secondary = c.gold, onSecondary = c.onAccent,
            background = c.ground, onBackground = c.ink, surface = c.ground, onSurface = c.ink,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerLow = c.card,
            onSurfaceVariant = c.muted, outline = c.inputBorder, outlineVariant = c.line, error = c.destructive,
            secondaryContainer = c.streakCard, onSecondaryContainer = c.accent, surfaceVariant = c.softFill,
            surfaceContainerHighest = c.softFill,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, secondary = c.gold, onSecondary = Color(0xFF2A1A00),
            background = c.ground, onBackground = c.ink, surface = c.ground, onSurface = c.ink,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, surfaceContainerLow = c.card,
            onSurfaceVariant = c.muted, outline = c.inputBorder, outlineVariant = c.line, error = c.destructive,
            secondaryContainer = c.streakCard, onSecondaryContainer = c.accent, surfaceVariant = c.softFill,
            surfaceContainerHighest = c.softFill,
        )
    }
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typography, content = content)
    }
}
