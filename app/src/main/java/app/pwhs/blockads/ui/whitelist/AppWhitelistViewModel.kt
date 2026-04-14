package app.pwhs.blockads.ui.whitelist

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.whitelist.data.AppInfoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class AppWhitelistViewModel(
    private val appPrefs: AppPreferences,
    application: Application
) : AndroidViewModel(application) {

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
                val pm = application.applicationContext.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    .filter { it.packageName != application.applicationContext.packageName }
                    .map { appInfo ->
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        AppInfoData(
                            packageName = appInfo.packageName,
                            label = appInfo.loadLabel(pm).toString(),
                            icon = appInfo.loadIcon(pm),
                            isSystemApp = isSystem
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }
            _installedApps.value = apps
            _isLoading.value = false
        }
    }

    fun refreshApps() {
        loadApps()
    }

    fun toggleApp(packageName: String) {
        viewModelScope.launch {
            appPrefs.toggleWhitelistedApp(packageName)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }
}
