package app.pwhs.blockads.ui.appearance

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.utils.LocaleHelper
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

    val showBottomNavLabels: StateFlow<Boolean> = appPrefs.showBottomNavLabels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val bottomNavSecondaryTab: StateFlow<String> = appPrefs.bottomNavSecondaryTab
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.BOTTOM_NAV_TAB_AUTO)

    val navRailPosition: StateFlow<String> = appPrefs.navRailPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.NAV_POSITION_LEFT)

    val floatingCapsuleNav: StateFlow<Boolean> = appPrefs.floatingCapsuleNav
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setBottomNavSecondaryTab(tab: String) {
        viewModelScope.launch { appPrefs.setBottomNavSecondaryTab(tab) }
    }

    fun setNavRailPosition(position: String) {
        viewModelScope.launch { appPrefs.setNavRailPosition(position) }
    }

    fun setFloatingCapsuleNav(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setFloatingCapsuleNav(enabled) }
    }

    fun setShowBottomNavLabels(show: Boolean) {
        viewModelScope.launch { appPrefs.setShowBottomNavLabels(show) }
    }

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
