package app.pwhs.blockads.ui.firewall

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.service.FirewallPromptManager
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.common.AppInfoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirewallViewModel(
    private val appPrefs: AppPreferences,
    private val firewallRuleDao: FirewallRuleDao,
    private val promptManager: FirewallPromptManager,
    application: Application
) : AndroidViewModel(application) {

    val firewallEnabled: StateFlow<Boolean> = appPrefs.firewallEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val promptEnabled: StateFlow<Boolean> = appPrefs.firewallPromptEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val firewallRules: StateFlow<List<FirewallRule>> = firewallRuleDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val enabledCount: StateFlow<Int> = firewallRuleDao.getEnabledCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _installedApps = MutableStateFlow<List<AppInfoData>>(emptyList())
    val installedApps: StateFlow<List<AppInfoData>> = _installedApps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _filterType = MutableStateFlow(FirewallFilterType.ALL)
    private val _selectedTab = MutableStateFlow(FirewallTab.USER)
    private val _configuringApp = MutableStateFlow<AppInfoData?>(null)

    private val _uiEffect = MutableSharedFlow<FirewallUiEffect>()
    val uiEffect: SharedFlow<FirewallUiEffect> = _uiEffect.asSharedFlow()

    private data class DataState(
        val enabled: Boolean = false,
        val promptEnabled: Boolean = false,
        val loading: Boolean = true,
        val apps: List<AppInfoData> = emptyList(),
        val rules: List<FirewallRule> = emptyList(),
        val count: Int = 0
    )

    private data class ControlState(
        val query: String = "",
        val filter: FirewallFilterType = FirewallFilterType.ALL,
        val tab: FirewallTab = FirewallTab.USER,
        val configApp: AppInfoData? = null
    )

    private val switchesFlow = combine(firewallEnabled, promptEnabled) { enabled, prompt -> enabled to prompt }

    private val dataFlow = combine(
        switchesFlow,
        isLoading,
        installedApps,
        firewallRules,
        enabledCount
    ) { switches, loading, apps, rules, count ->
        DataState(switches.first, switches.second, loading, apps, rules, count)
    }

    private val controlFlow = combine(
        _searchQuery,
        _filterType,
        _selectedTab,
        _configuringApp
    ) { query, filter, tab, configApp ->
        ControlState(query, filter, tab, configApp)
    }

    val uiState: StateFlow<FirewallUiState> = combine(
        dataFlow,
        controlFlow
    ) { data, ctrl ->
        val userList = data.apps.filter { !it.isSystemApp }
        val systemList = data.apps.filter { it.isSystemApp }
        val rulesMap = data.rules.associateBy { it.packageName }
        FirewallUiState(
            isFirewallEnabled = data.enabled,
            isPromptEnabled = data.promptEnabled,
            isLoading = data.loading,
            searchQuery = ctrl.query,
            filterType = ctrl.filter,
            selectedTab = ctrl.tab,
            userApps = userList,
            systemApps = systemList,
            rulesMap = rulesMap,
            enabledCount = data.count,
            configuringApp = ctrl.configApp
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FirewallUiState()
    )

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _isLoading.value = true
            val apps = withContext(Dispatchers.IO) {
                val pm = application.applicationContext.packageManager
                val existingBlockedPackages = runCatching {
                    firewallRuleDao.getAll().first().map { it.packageName }.toSet()
                }.getOrDefault(emptySet())

                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { appInfo ->
                        val isSelf = appInfo.packageName == application.applicationContext.packageName
                        if (isSelf) return@filter false
                        val hasInternet = hasInternetPermission(pm, appInfo.packageName)
                        (appInfo.enabled && hasInternet) || existingBlockedPackages.contains(appInfo.packageName)
                    }
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

    private fun hasInternetPermission(pm: PackageManager, packageName: String): Boolean {
        return try {
            val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            pkgInfo.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
        } catch (_: Exception) {
            false
        }
    }

    fun processIntent(intent: FirewallUiIntent) {
        when (intent) {
            is FirewallUiIntent.SetFirewallEnabled -> setFirewallEnabled(intent.enabled)
            is FirewallUiIntent.SetPromptEnabled -> setPromptEnabled(intent.enabled)
            is FirewallUiIntent.UpdateSearchQuery -> _searchQuery.value = intent.query
            is FirewallUiIntent.SelectFilterType -> _filterType.value = intent.filterType
            is FirewallUiIntent.SelectTab -> _selectedTab.value = intent.tab
            is FirewallUiIntent.ToggleAppBlock -> toggleAppFirewall(intent.packageName)
            is FirewallUiIntent.OpenAppConfig -> _configuringApp.value = intent.app
            is FirewallUiIntent.CloseAppConfig -> _configuringApp.value = null
            is FirewallUiIntent.SaveRule -> {
                saveRule(intent.rule)
                _configuringApp.value = null
            }
            is FirewallUiIntent.DeleteRule -> {
                deleteRule(intent.packageName)
                _configuringApp.value = null
            }
            is FirewallUiIntent.ToggleAllUserApps -> {
                val userPackages = _installedApps.value.filter { !it.isSystemApp }
                val allEnabled = userPackages.isNotEmpty() && userPackages.all { uiState.value.isBlocked(it.packageName) }
                if (allEnabled) disableAllUserApps() else enableAllUserApps()
            }
            is FirewallUiIntent.ToggleAllSystemApps -> {
                val sysPackages = _installedApps.value.filter { it.isSystemApp }
                val allEnabled = sysPackages.isNotEmpty() && sysPackages.all { uiState.value.isBlocked(it.packageName) }
                if (allEnabled) disableAllSystemApps() else enableAllSystemApps()
            }
            is FirewallUiIntent.RefreshApps -> refreshApps()
        }
    }

    fun refreshApps() {
        loadApps()
    }

    fun setFirewallEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setFirewallEnabled(enabled)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun setPromptEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setFirewallPromptEnabled(enabled)
            if (enabled) appPrefs.setFirewallEnabled(true) else promptManager.clearAll()
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun toggleAppFirewall(packageName: String) {
        viewModelScope.launch {
            val existing = firewallRuleDao.getByPackageName(packageName)
            when {
                existing == null -> firewallRuleDao.insert(FirewallRule(packageName = packageName))
                !existing.isEnabled -> firewallRuleDao.insert(existing.copy(isEnabled = true))
                // In prompt mode, unblocking is remembered as "allowed" so the app is not asked again
                promptEnabled.value -> firewallRuleDao.insert(existing.copy(isEnabled = false))
                else -> firewallRuleDao.deleteByPackageName(packageName)
            }
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun saveRule(rule: FirewallRule) {
        viewModelScope.launch {
            firewallRuleDao.insert(rule)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun deleteRule(packageName: String) {
        viewModelScope.launch {
            firewallRuleDao.deleteByPackageName(packageName)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun enableAllUserApps() {
        viewModelScope.launch {
            val userPackages = _installedApps.value
                .filter { !it.isSystemApp }
                .map { FirewallRule(packageName = it.packageName) }
            firewallRuleDao.insertAll(userPackages)
            blockExplicitlyAllowed(userPackages)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun disableAllUserApps() {
        viewModelScope.launch {
            val userPackages = _installedApps.value
                .filter { !it.isSystemApp }
                .map { it.packageName }
            firewallRuleDao.deleteByPackageNames(userPackages)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun enableAllSystemApps() {
        viewModelScope.launch {
            val systemPackages = _installedApps.value
                .filter { it.isSystemApp }
                .map { FirewallRule(packageName = it.packageName) }
            firewallRuleDao.insertAll(systemPackages)
            blockExplicitlyAllowed(systemPackages)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    fun disableAllSystemApps() {
        viewModelScope.launch {
            val systemPackages = _installedApps.value
                .filter { it.isSystemApp }
                .map { it.packageName }
            firewallRuleDao.deleteByPackageNames(systemPackages)
            ServiceController.requestRestart(getApplication<Application>().applicationContext)
        }
    }

    // insertAll ignores existing rows, so remembered "allowed" rules must be flipped explicitly
    private suspend fun blockExplicitlyAllowed(rules: List<FirewallRule>) {
        val rulesMap = uiState.value.rulesMap
        rules.mapNotNull { rulesMap[it.packageName] }
            .filter { !it.isEnabled }
            .forEach { firewallRuleDao.insert(it.copy(isEnabled = true)) }
    }
}
