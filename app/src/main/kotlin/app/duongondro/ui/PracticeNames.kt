package app.duongondro.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.duongondro.R
import app.duongondro.core.Practice

/**
 * A built-in practice's names as each country's practice books have them
 * (res/values-<lang>/practice_names.xml, from the iOS app's Practices.xcstrings),
 * falling back to English; custom practices show what the person typed.
 */
@Composable
fun Practice.shownName(): String = if (isCustom) name else names[id]?.first?.let { stringResource(it) } ?: name

@Composable
fun Practice.shownSecondName(): String? = secondName?.let { if (isCustom) it else names[id]?.second?.let { r -> stringResource(r) } ?: it }

private val names: Map<String, Pair<Int, Int?>> = mapOf(
    "short-refuge" to (R.string.practice_short_refuge_name to null),
    "refuge" to (R.string.practice_refuge_name to R.string.practice_refuge_second),
    "dorje-sempa" to (R.string.practice_dorje_sempa_name to R.string.practice_dorje_sempa_second),
    "mandala" to (R.string.practice_mandala_name to null),
    "guru-yoga" to (R.string.practice_guru_yoga_name to R.string.practice_guru_yoga_second),
    "8th-karmapa" to (R.string.practice_8th_karmapa_name to null),
    "16th-karmapa" to (R.string.practice_16th_karmapa_name to null),
    "chenrezig" to (R.string.practice_chenrezig_name to R.string.practice_chenrezig_second),
    "amitabha" to (R.string.practice_amitabha_name to R.string.practice_amitabha_second),
)
