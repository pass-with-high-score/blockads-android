package app.pwhs.blockads.data

import kotlinx.serialization.Serializable

@Serializable
data class SettingsBackup(
    val version: Int = 1,
    val upstreamDns: String = AppPreferences.DEFAULT_UPSTREAM_DNS,
    val fallbackDns: String = AppPreferences.DEFAULT_FALLBACK_DNS,
    val autoReconnect: Boolean = true,
    val themeMode: String = AppPreferences.THEME_SYSTEM,
    val appLanguage: String = AppPreferences.LANGUAGE_SYSTEM,
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
