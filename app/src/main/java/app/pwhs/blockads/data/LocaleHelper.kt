package app.pwhs.blockads.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    /**
     * Apply locale change at runtime.
     * On API 33+ uses LocaleManager (per-app language setting).
     * On older APIs, the activity will need to recreate.
     */
    fun setLocale(context: Context, languageTag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = if (languageTag == AppPreferences.LANGUAGE_SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageTag)
            }
        }
        // For pre-33, the locale is applied via attachBaseContext wrapping.
        // The activity needs to be recreated after saving the preference.
    }

    /**
     * Wrap the base context with the correct locale.
     * Call this from Activity.attachBaseContext() for pre-API 33 support.
     * On API 33+, LocaleManager handles it automatically.
     */
    fun wrapContext(context: Context, languageTag: String): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        if (languageTag == AppPreferences.LANGUAGE_SYSTEM) return context

        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }
}
