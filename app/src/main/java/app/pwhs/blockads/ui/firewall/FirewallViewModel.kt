package app.pwhs.blockads.ui.firewall

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.FirewallRule
import app.pwhs.blockads.data.FirewallRuleDao
import app.pwhs.blockads.ui.whitelist.data.AppInfoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirewallViewModel(
    private val appPrefs: AppPreferences,
    private val firewallRuleDao: FirewallRuleDao,
    application: Application
) : AndroidViewModel(application) {

    val firewallEnabled: StateFlow<Boolean> = appPrefs.firewallEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val firewallRules: StateFlow<List<FirewallRule>> = firewallRuleDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val enabledCount: StateFlow<Int> = firewallRuleDao.getEnabledCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
                val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val launchablePackages = pm.queryIntentActivities(launchIntent, 0)
                    .map { it.activityInfo.packageName }
                    .toSet()

                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName in launchablePackages }
                    .filter { it.packageName != application.applicationContext.packageName }
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

    fun setFirewallEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setFirewallEnabled(enabled)
        }
    }

    fun toggleAppFirewall(packageName: String) {
        viewModelScope.launch {
            val existing = firewallRuleDao.getByPackageName(packageName)
            if (existing != null) {
                firewallRuleDao.deleteByPackageName(packageName)
            } else {
                firewallRuleDao.insert(
                    FirewallRule(packageName = packageName)
                )
            }
        }
    }

    fun saveRule(rule: FirewallRule) {
        viewModelScope.launch {
            firewallRuleDao.insert(rule)
        }
    }

    fun deleteRule(packageName: String) {
        viewModelScope.launch {
            firewallRuleDao.deleteByPackageName(packageName)
        }
    }
}
