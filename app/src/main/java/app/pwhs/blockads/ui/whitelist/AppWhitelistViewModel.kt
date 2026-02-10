package app.pwhs.blockads.ui.whitelist

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfoData(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable
)

class AppWhitelistViewModel(
    private val appPrefs: AppPreferences,
    private val context: Context,
) : ViewModel() {

    val whitelistedApps: StateFlow<Set<String>> = appPrefs.whitelistedApps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _installedApps = MutableStateFlow<List<AppInfoData>>(emptyList())
    val installedApps: StateFlow<List<AppInfoData>> = _installedApps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _isLoading.value = true
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val launchablePackages = pm.queryIntentActivities(launchIntent, 0)
                    .map { it.activityInfo.packageName }
                    .toSet()

                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName in launchablePackages }
                    .filter { it.packageName != context.packageName }
                    .map { appInfo ->
                        AppInfoData(
                            packageName = appInfo.packageName,
                            label = appInfo.loadLabel(pm).toString(),
                            icon = appInfo.loadIcon(pm)
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
            _installedApps.value = apps
            _isLoading.value = false
        }
    }

    fun toggleApp(packageName: String) {
        viewModelScope.launch {
            appPrefs.toggleWhitelistedApp(packageName)
        }
    }
}

