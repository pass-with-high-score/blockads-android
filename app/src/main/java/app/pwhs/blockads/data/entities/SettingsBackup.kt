package app.pwhs.blockads.data.entities

import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.serialization.Serializable

@Serializable
data class SettingsBackup(
    val version: Int = 1,
    val upstreamDns: String = AppPreferences.Companion.DEFAULT_UPSTREAM_DNS,
    val fallbackDns: String = AppPreferences.Companion.DEFAULT_FALLBACK_DNS,
    val autoReconnect: Boolean = true,
    val themeMode: String = AppPreferences.Companion.THEME_SYSTEM,
    val appLanguage: String = AppPreferences.Companion.LANGUAGE_SYSTEM,
    val dnsResponseType: String = AppPreferences.Companion.DNS_RESPONSE_CUSTOM_IP,
    val safeSearchEnabled: Boolean = false,
    val youtubeRestrictedMode: Boolean = false,
    val dailySummaryEnabled: Boolean = true,
    val milestoneNotificationsEnabled: Boolean = true,
    val activeProfileType: String = "",
    val highContrast: Boolean = false,
    val firewallEnabled: Boolean = false,
    val filterLists: List<FilterListBackup> = emptyList(),
    val whitelistDomains: List<String> = emptyList(),
    val whitelistedApps: List<String> = emptyList(),
    val customRules: List<String> = emptyList(),
    val firewallRules: List<FirewallRuleBackup> = emptyList()
)

@Serializable
data class FilterListBackup(
    val name: String,
    val url: String,
    val isEnabled: Boolean = true
)

@Serializable
data class FirewallRuleBackup(
    val packageName: String,
    val blockWifi: Boolean = true,
    val blockMobileData: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = 22,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 6,
    val scheduleEndMinute: Int = 0,
    val isEnabled: Boolean = true
)
