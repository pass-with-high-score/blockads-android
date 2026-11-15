package app.pwhs.blockads.ui.firewall

import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.ui.common.AppInfoData

enum class FirewallFilterType {
    ALL,
    BLOCKED,
    ALLOWED
}

enum class FirewallTab {
    USER,
    SYSTEM
}

data class FirewallUiState(
    val isFirewallEnabled: Boolean = false,
    val isPromptEnabled: Boolean = false,
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val filterType: FirewallFilterType = FirewallFilterType.ALL,
    val selectedTab: FirewallTab = FirewallTab.USER,
    val userApps: List<AppInfoData> = emptyList(),
    val systemApps: List<AppInfoData> = emptyList(),
    val rulesMap: Map<String, FirewallRule> = emptyMap(),
    val enabledCount: Int = 0,
    val configuringApp: AppInfoData? = null
) {
    val currentApps: List<AppInfoData>
        get() = when (selectedTab) {
            FirewallTab.USER -> userApps
            FirewallTab.SYSTEM -> systemApps
        }

    val filteredApps: List<AppInfoData>
        get() = currentApps.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (filterType) {
                FirewallFilterType.ALL -> true
                FirewallFilterType.BLOCKED -> isBlocked(app.packageName)
                FirewallFilterType.ALLOWED -> !isBlocked(app.packageName)
            }
            matchesSearch && matchesFilter
        }

    /** A stored rule with isEnabled = false is a remembered "allow", not a block. */
    fun isBlocked(packageName: String): Boolean = rulesMap[packageName]?.isEnabled == true
}

sealed interface FirewallUiIntent {
    data class SetFirewallEnabled(val enabled: Boolean) : FirewallUiIntent
    data class SetPromptEnabled(val enabled: Boolean) : FirewallUiIntent
    data class UpdateSearchQuery(val query: String) : FirewallUiIntent
    data class SelectFilterType(val filterType: FirewallFilterType) : FirewallUiIntent
    data class SelectTab(val tab: FirewallTab) : FirewallUiIntent
    data class ToggleAppBlock(val packageName: String) : FirewallUiIntent
    data class OpenAppConfig(val app: AppInfoData) : FirewallUiIntent
    data object CloseAppConfig : FirewallUiIntent
    data class SaveRule(val rule: FirewallRule) : FirewallUiIntent
    data class DeleteRule(val packageName: String) : FirewallUiIntent
    data object ToggleAllUserApps : FirewallUiIntent
    data object ToggleAllSystemApps : FirewallUiIntent
    data object RefreshApps : FirewallUiIntent
}

sealed interface FirewallUiEffect {
    data class ShowToast(val message: String) : FirewallUiEffect
    data class OpenAppSettings(val packageName: String) : FirewallUiEffect
}
