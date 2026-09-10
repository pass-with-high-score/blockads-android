package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppearancePreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
        val KEY_SHOW_BOTTOM_NAV_LABELS = booleanPreferencesKey("show_bottom_nav_labels")

        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"

        const val ACCENT_GREEN = "green"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_PURPLE = "purple"
        const val ACCENT_ORANGE = "orange"
        const val ACCENT_PINK = "pink"
        const val ACCENT_TEAL = "teal"
        const val ACCENT_GREY = "grey"
        const val ACCENT_DYNAMIC = "dynamic"

        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_VI = "vi"
        const val LANGUAGE_JA = "ja"
        const val LANGUAGE_KO = "ko"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_TH = "th"
        const val LANGUAGE_ES = "es"
        const val LANGUAGE_RU = "ru"
        const val LANGUAGE_IT = "it"
        const val LANGUAGE_AR = "ar"
        const val LANGUAGE_TR = "tr"
        const val LANGUAGE_PL = "pl"
        const val LANGUAGE_IN = "in"
        const val LANGUAGE_PT_BR = "pt-BR"
        const val LANGUAGE_UK = "uk"
        const val LANGUAGE_DE = "de"
        const val LANGUAGE_CS = "cs"
        const val LANGUAGE_IW = "iw"
        const val LANGUAGE_FR = "fr"
        const val LANGUAGE_KK = "kk"
    }

    val themeMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: THEME_SYSTEM
    }

    val appLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_APP_LANGUAGE] ?: LANGUAGE_SYSTEM
    }

    val accentColor: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_ACCENT_COLOR] ?: ACCENT_GREEN
    }

    val showBottomNavLabels: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SHOW_BOTTOM_NAV_LABELS] ?: true
    }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
    }

    suspend fun setAppLanguage(language: String) {
        dataStore.edit { prefs ->
            prefs[KEY_APP_LANGUAGE] = language
        }
    }

    suspend fun setAccentColor(color: String) {
        dataStore.edit { prefs ->
            prefs[KEY_ACCENT_COLOR] = color
        }
    }

    suspend fun setShowBottomNavLabels(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_BOTTOM_NAV_LABELS] = show
        }
    }
}
