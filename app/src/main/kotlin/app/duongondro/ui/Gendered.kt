package app.duongondro.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.duongondro.R
import app.duongondro.core.Gender

/**
 * Strings that conjugate for someone's grammatical gender (design: Localisation ›
 * Grammatical gender). The neutral form is the string itself ("začal(a) si");
 * `<name>_male` and `<name>_female` (strings_gendered.xml) hold the gendered ones.
 * Their defaults are aliases of the neutral string, so a language that does not
 * conjugate falls back to its own neutral form, never to English.
 */
enum class GenderedString(@StringRes val neutral: Int, @StringRes val male: Int, @StringRes val female: Int) {
    FinishedNgondro(R.string.q_finished_ngondro, R.string.q_finished_ngondro_male, R.string.q_finished_ngondro_female),
    FinishedShortRefuge(R.string.q_finished_short_refuge, R.string.q_finished_short_refuge_male, R.string.q_finished_short_refuge_female),
    StartedAfterMidnight(R.string.started_after_midnight, R.string.started_after_midnight_male, R.string.started_after_midnight_female);

    @StringRes
    fun id(gender: Gender?): Int = when (gender) {
        Gender.Male -> male
        Gender.Female -> female
        Gender.Nonbinary, null -> neutral
    }
}

@Composable
fun stringResource(s: GenderedString, gender: Gender?, vararg args: Any): String = stringResource(s.id(gender), *args)
