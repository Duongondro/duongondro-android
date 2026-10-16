package app.duongondro

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/**
 * [this] in the language chosen in Settings, for text made outside an activity (reminders,
 * the export): on API 28–32 AppCompat applies that language to activities only, so the
 * application context would still speak the phone's.
 */
fun Context.inAppLanguage(): Context {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return this
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
    return createConfigurationContext(config)
}
