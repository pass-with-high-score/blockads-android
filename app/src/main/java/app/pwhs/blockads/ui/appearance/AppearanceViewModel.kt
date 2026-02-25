package app.pwhs.blockads.ui.appearance

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.LocaleHelper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppearanceViewModel(
    private val appPrefs: AppPreferences,
    application: Application,
) : AndroidViewModel(application) {

    val themeMode: StateFlow<String> = appPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.THEME_SYSTEM)

    val appLanguage: StateFlow<String> = appPrefs.appLanguage
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.LANGUAGE_SYSTEM
        )

    val accentColor: StateFlow<String> = appPrefs.accentColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.ACCENT_GREEN)

    fun setThemeMode(mode: String) {
        viewModelScope.launch { appPrefs.setThemeMode(mode) }
    }

    fun setAppLanguage(language: String) {
        viewModelScope.launch {
            appPrefs.setAppLanguage(language)
            LocaleHelper.setLocale(getApplication<Application>().applicationContext, language)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                (getApplication<Application>().applicationContext as? Activity)?.recreate()
            }
        }
    }

    fun setAccentColor(color: String) {
        viewModelScope.launch { appPrefs.setAccentColor(color) }
    }
}
