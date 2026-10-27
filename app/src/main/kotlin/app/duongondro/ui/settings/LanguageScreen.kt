package app.duongondro.ui.settings

import android.content.res.Resources
import android.icu.text.Collator
import android.icu.util.ULocale
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.core.os.LocaleListCompat
import app.duongondro.R
import app.duongondro.ui.CardSection
import app.duongondro.ui.RowDivider
import app.duongondro.ui.theme.Size
import app.duongondro.ui.theme.Space
import app.duongondro.ui.theme.Theme

/** Where people send feedback and better wording. */
internal const val FEEDBACK_ADDRESS = "duongondro@moroz.dev"

/** Languages a native speaker has read; the rest say they are machine-translated. */
private val READ_BY_A_NATIVE_SPEAKER = setOf("en", "pl")

/**
 * Settings › Language (design: Settings › Language): System, naming the phone's
 * language, then the ten languages in their own names (CLDR, never
 * re-capitalised), alphabetically, Latin scripts before Cyrillic, each with its
 * name in the app's language beneath. Applied with AppCompat's per-app locales.
 */
@Composable
fun LanguageScreen(back: () -> Unit) {
    val appLocale = LocalConfiguration.current.locales[0]
    var current by remember { mutableStateOf(AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language) }
    val collator = remember { Collator.getInstance(ULocale.ROOT) }
    val ordered = remember {
        LANGUAGES.sortedWith(compareBy<String> { if (nativeName(it).first().isCyrillic()) 1 else 0 }
            .thenComparator { a, b -> collator.compare(nativeName(a), nativeName(b)) })
    }
    val machine = stringResource(R.string.machine_translated)
    // The language the phone would give the app: its first preference we have, else English.
    val phone = remember {
        val system = Resources.getSystem().configuration.locales
        (0 until system.size()).map { system[it].language }.firstOrNull { it in LANGUAGES } ?: "en"
    }
    fun choose(code: String?) {
        current = code
        AppCompatDelegate.setApplicationLocales(code?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList())
    }
    Page(stringResource(R.string.language), back) {
        CardSection {
            Column(Modifier.selectableGroup()) {
                LanguageOption(stringResource(R.string.system_default), stringResource(R.string.phone_language, nativeName(phone)), current == null) { choose(null) }
                ordered.forEach { code ->
                    RowDivider()
                    val inApp = java.util.Locale.forLanguageTag(code).getDisplayLanguage(appLocale)
                    val second = buildList {
                        if (code != appLocale.language) add(inApp)
                        if (code !in READ_BY_A_NATIVE_SPEAKER) add(machine)
                    }.joinToString(" · ").ifEmpty { null }
                    LanguageOption(nativeName(code), second, current == code) { choose(code) }
                }
            }
        }
        val prefix = stringResource(R.string.translation_help)
        Text(
            buildAnnotatedString {
                append(prefix)
                append(" ")
                withLink(LinkAnnotation.Url("mailto:$FEEDBACK_ADDRESS",
                    TextLinkStyles(SpanStyle(color = Theme.colors.accent, fontWeight = FontWeight.SemiBold)))) { append(FEEDBACK_ADDRESS) }
            },
            style = Theme.type.footnote, color = Theme.colors.muted, modifier = Modifier.padding(horizontal = Space.l),
        )
    }
}

@Composable
private fun LanguageOption(title: String, second: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = Size.minTap).padding(horizontal = Space.l, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Theme.type.body, color = Theme.colors.ink)
            second?.let { Text(it, style = Theme.type.secondary, color = Theme.colors.muted) }
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Theme.colors.accent)
    }
}

private fun Char.isCyrillic(): Boolean = Character.UnicodeBlock.of(this) == Character.UnicodeBlock.CYRILLIC
