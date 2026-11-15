package app.pwhs.blockads.ui.appmanagement

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.entities.CountryTopDomain
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.appmanagement.data.AppDataUsageProvider
import app.pwhs.blockads.ui.appmanagement.data.AppManagementData
import app.pwhs.blockads.ui.appmanagement.data.AppSortOption
import app.pwhs.blockads.ui.appmanagement.data.AppUsageTimeRange
import app.pwhs.blockads.ui.appmanagement.data.UidTrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber


@OptIn(ExperimentalCoroutinesApi::class)
class AppManagementViewModel(
    private val appPrefs: AppPreferences,
    private val dnsLogDao: DnsLogDao,
    application: Application
) : AndroidViewModel(application) {

    private val _installedApps = MutableStateFlow<List<AppManagementData>>(emptyList())
    private val _usageByUid = MutableStateFlow<Map<Int, UidTrafficStats>>(emptyMap())

    private val _hasUsageStatsPermission = MutableStateFlow(false)
    val hasUsageStatsPermission: StateFlow<Boolean> = _hasUsageStatsPermission.asStateFlow()

    private val _usageTimeRange = MutableStateFlow(AppUsageTimeRange.TODAY)
    val usageTimeRange: StateFlow<AppUsageTimeRange> = _usageTimeRange.asStateFlow()

    private val _selectedAppPackage = MutableStateFlow<String?>(null)
    val selectedAppPackage: StateFlow<String?> = _selectedAppPackage.asStateFlow()

    val selectedAppDomains: StateFlow<List<CountryTopDomain>> = _selectedAppPackage
        .flatMapLatest { pkg ->
            if (pkg.isNullOrBlank()) flowOf(emptyList()) else dnsLogDao.getAppTopDomains(pkg)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sortOption = MutableStateFlow(AppSortOption.NAME)
    val sortOption: StateFlow<AppSortOption> = _sortOption.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _totalAppCount = MutableStateFlow(0)
    val totalAppCount: StateFlow<Int> = _totalAppCount.asStateFlow()

    val apps: StateFlow<List<AppManagementData>> = combine(
        _installedApps,
        _usageByUid,
        appPrefs.whitelistedApps,
        dnsLogDao.getPerAppStats().distinctUntilChanged(),
        combine(_searchQuery, _sortOption) { q, s -> q to s }
    ) { installedApps, usageMap, whitelisted, stats, (query, sort) ->
        // Every row carries both the label and the package name, and the package
        // name is what identifies the app: labels are not unique, so keying on them
        // merged the stats of two apps that share one.
        val statsByPackage = stats.associateBy { it.packageName }
        // Only a row with no package identity of its own may be matched by label:
        // the writer stores the friendly name in both fields when a UID owns no
        // package, and history recorded before the column existed is blank.
        // Anything else would leak one app's stats to another sharing its label.
        val statsByLabel = stats
            .filter {
                it.packageName.isBlank() ||
                    (it.packageName == it.appName && !it.packageName.contains("."))
            }
            .associateBy { it.appName }

        var result = installedApps.map { app ->
            val stat = statsByPackage[app.packageName] ?: statsByLabel[app.label]
            val usage = usageMap[app.uid]
            app.copy(
                totalQueries = stat?.totalQueries ?: 0,
                blockedQueries = stat?.blockedQueries ?: 0,
                rxBytes = usage?.rxBytes ?: 0L,
                txBytes = usage?.txBytes ?: 0L,
                wifiRxBytes = usage?.wifiRxBytes ?: 0L,
                wifiTxBytes = usage?.wifiTxBytes ?: 0L,
                mobileRxBytes = usage?.mobileRxBytes ?: 0L,
                mobileTxBytes = usage?.mobileTxBytes ?: 0L,
                isWhitelisted = app.packageName in whitelisted
            )
        }

        if (query.isNotBlank()) {
            result = result.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }

        when (sort) {
            AppSortOption.NAME -> result.sortedBy { it.label.lowercase() }
            AppSortOption.QUERIES -> result.sortedByDescending { it.totalQueries }
            AppSortOption.BLOCKED -> result.sortedByDescending { it.blockedQueries }
            AppSortOption.DATA_USAGE -> result.sortedByDescending { it.totalBytes }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val (apps, hasPerm, usageMap) = withContext(Dispatchers.IO) {
                    val ctx = application.applicationContext
                    val pm = ctx.packageManager
                    val loadedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        .filter { it.packageName != ctx.packageName }
                        .map { appInfo ->
                            AppManagementData(
                                packageName = appInfo.packageName,
                                label = appInfo.loadLabel(pm).toString(),
                                icon = appInfo.loadIcon(pm),
                                uid = appInfo.uid,
                                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                    val (perm, usage) = AppDataUsageProvider.loadUsageByUid(
                        context = ctx,
                        uids = loadedApps.map { it.uid },
                        timeRange = _usageTimeRange.value
                    )
                    Triple(loadedApps, perm, usage)
                }
                _installedApps.value = apps
                _totalAppCount.value = apps.size
                _hasUsageStatsPermission.value = hasPerm
                _usageByUid.value = usageMap
            } catch (e: Exception) {
                Timber.e(e, "Failed to load apps")
                _installedApps.value = emptyList()
                _totalAppCount.value = 0
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshUsageStats() {
        val currentApps = _installedApps.value
        if (currentApps.isEmpty()) return
        viewModelScope.launch {
            val (perm, usage) = withContext(Dispatchers.IO) {
                AppDataUsageProvider.loadUsageByUid(
                    context = application.applicationContext,
                    uids = currentApps.map { it.uid },
                    timeRange = _usageTimeRange.value
                )
            }
            _hasUsageStatsPermission.value = perm
            _usageByUid.value = usage
        }
    }

    fun setUsageTimeRange(range: AppUsageTimeRange) {
        if (_usageTimeRange.value == range) return
        _usageTimeRange.value = range
        refreshUsageStats()
    }

    fun selectApp(packageName: String?) {
        _selectedAppPackage.value = packageName
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

    fun setSortOption(option: AppSortOption) {
        _sortOption.value = option
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
