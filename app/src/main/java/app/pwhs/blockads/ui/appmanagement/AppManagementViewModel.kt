package app.pwhs.blockads.ui.appmanagement

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsLogDao
import app.pwhs.blockads.ui.appmanagement.data.AppManagementData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppSortOption {
    NAME, QUERIES, BLOCKED
}

class AppManagementViewModel(
    private val appPrefs: AppPreferences,
    private val dnsLogDao: DnsLogDao,
    application: Application
) : AndroidViewModel(application) {

    private val _installedApps = MutableStateFlow<List<AppManagementData>>(emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sortOption = MutableStateFlow(AppSortOption.NAME)
    val sortOption: StateFlow<AppSortOption> = _sortOption.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val apps: StateFlow<List<AppManagementData>> = combine(
        _installedApps,
        appPrefs.whitelistedApps,
        dnsLogDao.getPerAppStats(),
        _searchQuery,
        _sortOption
    ) { installedApps, whitelisted, stats, query, sort ->
        val statsByName = stats.associateBy { it.appName }

        var result = installedApps.map { app ->
            // AppNameResolver stores label (e.g. "Chrome"), fallback to package name
            val stat = statsByName[app.label] ?: statsByName[app.packageName]
            app.copy(
                totalQueries = stat?.totalQueries ?: 0,
                blockedQueries = stat?.blockedQueries ?: 0,
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
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                        AppManagementData(
                            packageName = appInfo.packageName,
                            label = appInfo.loadLabel(pm).toString(),
                            icon = appInfo.loadIcon(pm),
                            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
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

    fun setSortOption(option: AppSortOption) {
        _sortOption.value = option
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
