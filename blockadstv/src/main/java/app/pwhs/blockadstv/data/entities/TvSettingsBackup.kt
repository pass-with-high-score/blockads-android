package app.pwhs.blockadstv.data.entities

import kotlinx.serialization.Serializable

@Serializable
data class TvSettingsBackup(
    val version: Int = 1,
    val upstreamDns: String = "9.9.9.9",
    val fallbackDns: String = "94.140.14.14",
    val dnsProtocol: String = "UDP",
    val dohUrl: String = "https://dns.quad9.net/dns-query",
    val odohRelayUrl: String = "",
    val isCustomDns: Boolean = false,
    val customDnsEndpoint: String = "",
    val dnsResponseType: String = "custom_ip",
    val autoReconnect: Boolean = true,
    val themeMode: String = "dark",
    val appLanguage: String = "system",
    val startOnBoot: Boolean = false,
    val excludeLan: Boolean = true,
    val preferredIpType: String = "auto",
    val recordDnsLogs: Boolean = true,
    val blockDohBypass: Boolean = true,
    val blockWebRtcLeak: Boolean = false,
    val filterHttp3: Boolean = false,
    val routingMode: String = "direct",
    val whitelistedApps: List<String> = emptyList(),
    // ProtectionPreferences
    val safeSearchEnabled: Boolean = false,
    val youtubeRestrictedMode: Boolean = false,
    val trustedNetworks: List<String> = emptyList(),
    val pauseOnTrustedEnabled: Boolean = false,
    val useSystemDnsOnTrustedEnabled: Boolean = false,
    // RulesPreferences
    val ruleManifestEnabled: Boolean = true,
    val modulesEnabled: Boolean = true,
    val rulesetEnabled: Boolean = true,
    // WireGuard
    val wgProfilesJson: String = "",
    val wgActiveProfileId: String? = null,
    val geoIpAutoUpdate: Boolean = true,
    val geoIpCustomUrl: String = "",
    // Database items
    val filterLists: List<TvFilterListBackup> = emptyList(),
    val whitelistDomains: List<String> = emptyList(),
    val customRules: List<TvCustomRuleBackup> = emptyList(),
    val firewallRules: List<TvFirewallRuleBackup> = emptyList(),
    val configProfiles: List<TvConfigProfileBackup> = emptyList(),
    val proxyProfilesJson: String = "",
    val proxySubscriptionsJson: String = "",
)

@Serializable
data class TvFilterListBackup(
    val name: String,
    val url: String,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val category: String = "AD",
)

@Serializable
data class TvCustomRuleBackup(
    val rule: String,
    val ruleType: String,
    val domain: String,
    val isEnabled: Boolean = true,
)

@Serializable
data class TvFirewallRuleBackup(
    val packageName: String,
    val isEnabled: Boolean = true,
)

@Serializable
data class TvConfigProfileBackup(
    val name: String,
    val content: String,
    val remoteUrl: String? = null,
    val autoUpdate: Boolean = false,
    val isActive: Boolean = false,
    val isBuiltIn: Boolean = false,
    val icon: String? = null,
)
