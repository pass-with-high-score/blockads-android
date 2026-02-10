package app.pwhs.blockads.data

import kotlinx.serialization.Serializable

@Serializable
data class SettingsBackup(
    val version: Int = 1,
    val upstreamDns: String = AppPreferences.DEFAULT_UPSTREAM_DNS,
    val autoReconnect: Boolean = true,
    val themeMode: String = AppPreferences.THEME_SYSTEM,
    val filterLists: List<FilterListBackup> = emptyList(),
    val whitelistDomains: List<String> = emptyList(),
    val whitelistedApps: List<String> = emptyList()
)

@Serializable
data class FilterListBackup(
    val name: String,
    val url: String,
    val isEnabled: Boolean = true
)
