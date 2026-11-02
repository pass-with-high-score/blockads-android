package app.pwhs.blockads.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.pwhs.blockads.data.datastore.prefs.AppearancePreferences
import app.pwhs.blockads.data.datastore.prefs.DnsPreferences
import app.pwhs.blockads.data.datastore.prefs.FilterPreferences
import app.pwhs.blockads.data.datastore.prefs.ShadowsocksPreferences
import app.pwhs.blockads.data.datastore.prefs.TailscalePreferences
import app.pwhs.blockads.data.datastore.prefs.VpnSecurityPreferences
import app.pwhs.blockads.data.datastore.prefs.WireGuardPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.data.entities.ProxySubscription
import app.pwhs.blockads.data.entities.ShadowsocksProfile
import app.pwhs.blockads.data.entities.WireGuardProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

val Context.blockAdsDataStore: DataStore<Preferences> by preferencesDataStore(name = "blockads_prefs")

class AppPreferences internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val directBootPrefs: DirectBootPreferences,
) {

    constructor(context: Context) : this(context.blockAdsDataStore, DirectBootPreferences(context))

    val dns = DnsPreferences(dataStore)
    val appearance = AppearancePreferences(dataStore)
    val filter = FilterPreferences(dataStore)
    val wireguard = WireGuardPreferences(dataStore)
    val vpnSecurity = VpnSecurityPreferences(dataStore)
    val shadowsocks = ShadowsocksPreferences(dataStore)
    val tailscale = TailscalePreferences(dataStore)

    companion object {
        const val ROUTING_MODE_DIRECT = WireGuardPreferences.ROUTING_MODE_DIRECT
        const val ROUTING_MODE_WIREGUARD = WireGuardPreferences.ROUTING_MODE_WIREGUARD
        const val ROUTING_MODE_ROOT = WireGuardPreferences.ROUTING_MODE_ROOT
        const val ROUTING_MODE_SHADOWSOCKS = ShadowsocksPreferences.ROUTING_MODE_SHADOWSOCKS
        const val ROUTING_MODE_PROXY_VPN = "proxy_vpn"

        const val PROXY_TYPE_WIREGUARD = ShadowsocksPreferences.PROXY_TYPE_WIREGUARD
        const val PROXY_TYPE_SHADOWSOCKS = ShadowsocksPreferences.PROXY_TYPE_SHADOWSOCKS
        const val PROXY_TYPE_NODE = ShadowsocksPreferences.PROXY_TYPE_NODE

        const val PREFERRED_IP_AUTO = VpnSecurityPreferences.PREFERRED_IP_AUTO
        const val PREFERRED_IP_IPV4 = VpnSecurityPreferences.PREFERRED_IP_IPV4
        const val PREFERRED_IP_IPV6 = VpnSecurityPreferences.PREFERRED_IP_IPV6

        const val PROTECTION_BASIC = FilterPreferences.PROTECTION_BASIC
        const val PROTECTION_STANDARD = FilterPreferences.PROTECTION_STANDARD
        const val PROTECTION_STRICT = FilterPreferences.PROTECTION_STRICT

        const val BUILD_MODE_ONLINE = FilterPreferences.BUILD_MODE_ONLINE
        const val BUILD_MODE_LOCAL = FilterPreferences.BUILD_MODE_LOCAL

        const val BOTTOM_NAV_TAB_AUTO = AppearancePreferences.BOTTOM_NAV_TAB_AUTO
        const val BOTTOM_NAV_TAB_FILTER = AppearancePreferences.BOTTOM_NAV_TAB_FILTER
        const val BOTTOM_NAV_TAB_VPN = AppearancePreferences.BOTTOM_NAV_TAB_VPN

        const val THEME_SYSTEM = AppearancePreferences.THEME_SYSTEM
        const val THEME_DARK = AppearancePreferences.THEME_DARK
        const val THEME_LIGHT = AppearancePreferences.THEME_LIGHT
        const val THEME_OLED = AppearancePreferences.THEME_OLED

        const val ACCENT_GREEN = AppearancePreferences.ACCENT_GREEN
        const val ACCENT_BLUE = AppearancePreferences.ACCENT_BLUE
        const val ACCENT_PURPLE = AppearancePreferences.ACCENT_PURPLE
        const val ACCENT_ORANGE = AppearancePreferences.ACCENT_ORANGE
        const val ACCENT_PINK = AppearancePreferences.ACCENT_PINK
        const val ACCENT_TEAL = AppearancePreferences.ACCENT_TEAL
        const val ACCENT_GREY = AppearancePreferences.ACCENT_GREY
        const val ACCENT_DYNAMIC = AppearancePreferences.ACCENT_DYNAMIC

        const val LANGUAGE_SYSTEM = AppearancePreferences.LANGUAGE_SYSTEM
        const val LANGUAGE_EN = AppearancePreferences.LANGUAGE_EN
        const val LANGUAGE_VI = AppearancePreferences.LANGUAGE_VI
        const val LANGUAGE_JA = AppearancePreferences.LANGUAGE_JA
        const val LANGUAGE_KO = AppearancePreferences.LANGUAGE_KO
        const val LANGUAGE_ZH = AppearancePreferences.LANGUAGE_ZH
        const val LANGUAGE_TH = AppearancePreferences.LANGUAGE_TH
        const val LANGUAGE_ES = AppearancePreferences.LANGUAGE_ES
        const val LANGUAGE_RU = AppearancePreferences.LANGUAGE_RU
        const val LANGUAGE_IT = AppearancePreferences.LANGUAGE_IT
        const val LANGUAGE_AR = AppearancePreferences.LANGUAGE_AR
        const val LANGUAGE_TR = AppearancePreferences.LANGUAGE_TR
        const val LANGUAGE_PL = AppearancePreferences.LANGUAGE_PL
        const val LANGUAGE_IN = AppearancePreferences.LANGUAGE_IN
        const val LANGUAGE_PT_BR = AppearancePreferences.LANGUAGE_PT_BR
        const val LANGUAGE_UK = AppearancePreferences.LANGUAGE_UK
        const val LANGUAGE_DE = AppearancePreferences.LANGUAGE_DE
        const val LANGUAGE_CS = AppearancePreferences.LANGUAGE_CS
        const val LANGUAGE_IW = AppearancePreferences.LANGUAGE_IW
        const val LANGUAGE_FR = AppearancePreferences.LANGUAGE_FR
        const val LANGUAGE_KK = AppearancePreferences.LANGUAGE_KK

        const val UPDATE_FREQUENCY_6H = FilterPreferences.UPDATE_FREQUENCY_6H
        const val UPDATE_FREQUENCY_12H = FilterPreferences.UPDATE_FREQUENCY_12H
        const val UPDATE_FREQUENCY_24H = FilterPreferences.UPDATE_FREQUENCY_24H
        const val UPDATE_FREQUENCY_48H = FilterPreferences.UPDATE_FREQUENCY_48H
        const val UPDATE_FREQUENCY_MANUAL = FilterPreferences.UPDATE_FREQUENCY_MANUAL

        const val NOTIFICATION_SILENT = FilterPreferences.NOTIFICATION_SILENT
        const val NOTIFICATION_NORMAL = FilterPreferences.NOTIFICATION_NORMAL
        const val NOTIFICATION_NONE = FilterPreferences.NOTIFICATION_NONE

        const val DNS_RESPONSE_NXDOMAIN = DnsPreferences.DNS_RESPONSE_NXDOMAIN
        const val DNS_RESPONSE_REFUSED = DnsPreferences.DNS_RESPONSE_REFUSED
        const val DNS_RESPONSE_CUSTOM_IP = DnsPreferences.DNS_RESPONSE_CUSTOM_IP

        const val DEFAULT_FILTER_URL = FilterPreferences.DEFAULT_FILTER_URL
        const val DEFAULT_UPSTREAM_DNS = DnsPreferences.DEFAULT_UPSTREAM_DNS
        const val DEFAULT_FALLBACK_DNS = DnsPreferences.DEFAULT_FALLBACK_DNS
        const val DEFAULT_DNS_PROTOCOL = DnsPreferences.DEFAULT_DNS_PROTOCOL
        const val DEFAULT_DOH_URL = DnsPreferences.DEFAULT_DOH_URL
        const val CUSTOM_DNS_PROVIDER_ID = "custom"
    }

    // ── VPN & Security Flows ─────────────────────────────────────────────
    val vpnEnabled: Flow<Boolean> get() = vpnSecurity.vpnEnabled
    val autoReconnect: Flow<Boolean> get() = vpnSecurity.autoReconnect
    val onboardingCompleted: Flow<Boolean> get() = vpnSecurity.onboardingCompleted
    val whitelistedApps: Flow<Set<String>> get() = vpnSecurity.whitelistedApps
    val dailySummaryEnabled: Flow<Boolean> get() = vpnSecurity.dailySummaryEnabled
    val milestoneNotificationsEnabled: Flow<Boolean> get() = vpnSecurity.milestoneNotificationsEnabled
    val lastMilestoneBlocked: Flow<Long> get() = vpnSecurity.lastMilestoneBlocked
    val lastSeenMilestoneDialog: Flow<Long> get() = vpnSecurity.lastSeenMilestoneDialog
    val activeProfileId: Flow<Long> get() = vpnSecurity.activeProfileId
    val recordDnsLogs: Flow<Boolean> get() = vpnSecurity.recordDnsLogs
    val firewallEnabled: Flow<Boolean> get() = vpnSecurity.firewallEnabled
    val crashReportingEnabled: Flow<Boolean> get() = vpnSecurity.crashReportingEnabled
    val hideFromRecents: Flow<Boolean> get() = vpnSecurity.hideFromRecents
    val trustedSsids: Flow<Set<String>> get() = vpnSecurity.trustedSsids
    val pauseOnTrustedEnabled: Flow<Boolean> get() = vpnSecurity.pauseOnTrustedEnabled
    val useSystemDnsOnTrustedEnabled: Flow<Boolean> get() = vpnSecurity.useSystemDnsOnTrustedEnabled
    val pausedByTrusted: Flow<Boolean> get() = vpnSecurity.pausedByTrusted
    val pausedTrustedSsid: Flow<String> get() = vpnSecurity.pausedTrustedSsid
    val httpsFilteringEnabled: Flow<Boolean> get() = vpnSecurity.httpsFilteringEnabled
    val filterHttp3: Flow<Boolean> get() = vpnSecurity.filterHttp3
    val vpnRevokedByAnotherApp: Flow<Boolean> get() = vpnSecurity.vpnRevokedByAnotherApp
    val stabilityTipDismissed: Flow<Boolean> get() = vpnSecurity.stabilityTipDismissed
    val stabilityTipDismissedAt: Flow<Long> get() = vpnSecurity.stabilityTipDismissedAt
    val stabilityTipLastBatteryIgnored: Flow<Boolean> get() = vpnSecurity.stabilityTipLastBatteryIgnored
    val stabilityTipLastAlwaysOn: Flow<Boolean> get() = vpnSecurity.stabilityTipLastAlwaysOn
    val dismissedFeatureTips: Flow<Set<String>> get() = vpnSecurity.dismissedFeatureTips
    val preferredIPType: Flow<String> get() = vpnSecurity.preferredIPType

    // ── DNS Flows ────────────────────────────────────────────────────────
    val upstreamDns: Flow<String> get() = dns.upstreamDns
    val fallbackDns: Flow<String> get() = dns.fallbackDns
    val dnsProtocol: Flow<DnsProtocol> get() = dns.dnsProtocol
    val dohUrl: Flow<String> get() = dns.dohUrl
    val odohRelayUrl: Flow<String> get() = dns.odohRelayUrl
    val dnsProviderId: Flow<String?> get() = dns.dnsProviderId
    val dnsResponseType: Flow<String> get() = dns.dnsResponseType
    val splitDnsZones: Flow<String> get() = dns.splitDnsZones
    val blockDohBypass: Flow<Boolean> get() = dns.blockDohBypass
    val blockWebRtcLeak: Flow<Boolean> get() = dns.blockWebRtcLeak

    // ── Appearance Flows ─────────────────────────────────────────────────
    val themeMode: Flow<String> get() = appearance.themeMode
    val appLanguage: Flow<String> get() = appearance.appLanguage
    val accentColor: Flow<String> get() = appearance.accentColor
    val showBottomNavLabels: Flow<Boolean> get() = appearance.showBottomNavLabels
    val bottomNavSecondaryTab: Flow<String> get() = appearance.bottomNavSecondaryTab
    suspend fun setBottomNavSecondaryTab(tab: String) = appearance.setBottomNavSecondaryTab(tab)

    // ── Filter Flows ─────────────────────────────────────────────────────
    val filterUrl: Flow<String> get() = filter.filterUrl
    val autoUpdateEnabled: Flow<Boolean> get() = filter.autoUpdateEnabled
    val autoUpdateFrequency: Flow<String> get() = filter.autoUpdateFrequency
    val autoUpdateWifiOnly: Flow<Boolean> get() = filter.autoUpdateWifiOnly
    val autoUpdateNotification: Flow<String> get() = filter.autoUpdateNotification
    val protectionLevel: Flow<String> get() = filter.protectionLevel
    val safeSearchEnabled: Flow<Boolean> get() = filter.safeSearchEnabled
    val youtubeRestrictedMode: Flow<Boolean> get() = filter.youtubeRestrictedMode
    val geoIpCustomUrl: Flow<String> get() = filter.geoIpCustomUrl
    val geoIpAutoUpdate: Flow<Boolean> get() = filter.geoIpAutoUpdate
    val filterBuildMode: Flow<String> get() = filter.filterBuildMode
    val customFilterTags: Flow<Set<String>> get() = filter.customFilterTags
    suspend fun addCustomFilterTag(tag: String) = filter.addCustomFilterTag(tag)
    suspend fun removeCustomFilterTag(tag: String) = filter.removeCustomFilterTag(tag)

    // ── WireGuard Flows ──────────────────────────────────────────────────
    val routingMode: Flow<String> get() = wireguard.routingMode
    val wgProfiles: Flow<List<WireGuardProfile>> get() = wireguard.wgProfiles
    val wgActiveProfileId: Flow<String?> get() = wireguard.wgActiveProfileId
    val excludeLan: Flow<Boolean> get() = wireguard.excludeLan
    val allowAppBypass: Flow<Boolean> get() = wireguard.allowAppBypass

    // ── Mutator & Snapshot Delegates ─────────────────────────────────────
    suspend fun setVpnEnabled(enabled: Boolean) {
        vpnSecurity.setVpnEnabled(enabled)
        directBootPrefs.wasVpnEnabled = enabled
    }
    suspend fun setAutoReconnect(enabled: Boolean) {
        vpnSecurity.setAutoReconnect(enabled)
        directBootPrefs.autoReconnect = enabled
    }

    suspend fun setOnboardingCompleted(completed: Boolean) = vpnSecurity.setOnboardingCompleted(completed)
    suspend fun setWhitelistedApps(apps: Set<String>) = vpnSecurity.setWhitelistedApps(apps)
    suspend fun toggleWhitelistedApp(packageName: String) = vpnSecurity.toggleWhitelistedApp(packageName)
    suspend fun getWhitelistedAppsSnapshot(): Set<String> = vpnSecurity.getWhitelistedAppsSnapshot()
    suspend fun setDailySummaryEnabled(enabled: Boolean) = vpnSecurity.setDailySummaryEnabled(enabled)
    suspend fun setMilestoneNotificationsEnabled(enabled: Boolean) = vpnSecurity.setMilestoneNotificationsEnabled(enabled)
    suspend fun setLastMilestoneBlocked(count: Long) = vpnSecurity.setLastMilestoneBlocked(count)
    suspend fun setLastSeenMilestoneDialog(milestone: Long) = vpnSecurity.setLastSeenMilestoneDialog(milestone)
    suspend fun setActiveProfileId(id: Long) = vpnSecurity.setActiveProfileId(id)
    suspend fun setRecordDnsLogs(enabled: Boolean) = vpnSecurity.setRecordDnsLogs(enabled)
    suspend fun setFirewallEnabled(enabled: Boolean) = vpnSecurity.setFirewallEnabled(enabled)
    suspend fun setHttpsFilteringEnabled(enabled: Boolean) = vpnSecurity.setHttpsFilteringEnabled(enabled)
    suspend fun getHttpsFilteringEnabledSnapshot(): Boolean = vpnSecurity.getHttpsFilteringEnabledSnapshot()
    suspend fun setFilterHttp3(enabled: Boolean) = vpnSecurity.setFilterHttp3(enabled)
    suspend fun getFilterHttp3Snapshot(): Boolean = vpnSecurity.getFilterHttp3Snapshot()
    suspend fun setSelectedBrowsers(packages: Set<String>) = vpnSecurity.setSelectedBrowsers(packages)
    fun getSelectedBrowsersSnapshot(): Set<String> = vpnSecurity.getSelectedBrowsersSnapshot()
    fun getSelectedBrowsersOrNullSnapshot(): Set<String>? = vpnSecurity.getSelectedBrowsersOrNullSnapshot()
    suspend fun setCrashReportingEnabled(enabled: Boolean) = vpnSecurity.setCrashReportingEnabled(enabled)
    suspend fun setHideFromRecents(enabled: Boolean) = vpnSecurity.setHideFromRecents(enabled)
    suspend fun setTrustedSsids(ssids: Set<String>) = vpnSecurity.setTrustedSsids(ssids)
    suspend fun toggleTrustedSsid(ssid: String) = vpnSecurity.toggleTrustedSsid(ssid)
    suspend fun setPauseOnTrustedEnabled(enabled: Boolean) = vpnSecurity.setPauseOnTrustedEnabled(enabled)
    suspend fun setUseSystemDnsOnTrustedEnabled(enabled: Boolean) = vpnSecurity.setUseSystemDnsOnTrustedEnabled(enabled)
    suspend fun getUseSystemDnsOnTrustedEnabledSnapshot(): Boolean = vpnSecurity.getUseSystemDnsOnTrustedEnabledSnapshot()
    suspend fun getTrustedSsidsSnapshot(): Set<String> = vpnSecurity.getTrustedSsidsSnapshot()
    suspend fun getPauseOnTrustedEnabledSnapshot(): Boolean = vpnSecurity.getPauseOnTrustedEnabledSnapshot()
    suspend fun setPausedByTrusted(value: Boolean, ssid: String = "") = vpnSecurity.setPausedByTrusted(value, ssid)
    suspend fun getPausedByTrustedSnapshot(): Boolean = vpnSecurity.getPausedByTrustedSnapshot()
    suspend fun setVpnRevokedByAnotherApp(value: Boolean) = vpnSecurity.setVpnRevokedByAnotherApp(value)
    suspend fun setStabilityTipDismissed(value: Boolean) = vpnSecurity.setStabilityTipDismissed(value)
    suspend fun recordStabilityTipDismissed(batteryIgnored: Boolean, alwaysOn: Boolean) =
        vpnSecurity.recordStabilityTipDismissed(batteryIgnored, alwaysOn)
    suspend fun updateStabilityTipHardwareState(batteryIgnored: Boolean, alwaysOn: Boolean) =
        vpnSecurity.updateStabilityTipHardwareState(batteryIgnored, alwaysOn)
    suspend fun dismissFeatureTip(tipId: String) = vpnSecurity.dismissFeatureTip(tipId)
    suspend fun getPreferredIPTypeSnapshot(): String = vpnSecurity.getPreferredIPTypeSnapshot()
    suspend fun setPreferredIPType(type: String) = vpnSecurity.setPreferredIPType(type)

    suspend fun setUpstreamDns(dnsServer: String) = dns.setUpstreamDns(dnsServer)
    suspend fun setFallbackDns(dnsServer: String) = dns.setFallbackDns(dnsServer)
    suspend fun setDnsProtocol(protocol: DnsProtocol) = dns.setDnsProtocol(protocol)
    suspend fun setDohUrl(url: String) = dns.setDohUrl(url)
    suspend fun setOdohRelayUrl(url: String) = dns.setOdohRelayUrl(url)
    suspend fun setDnsProviderId(providerId: String?) = dns.setDnsProviderId(providerId)
    suspend fun setDnsResponseType(responseType: String) = dns.setDnsResponseType(responseType)
    suspend fun setSplitDnsZones(zones: String) = dns.setSplitDnsZones(zones)
    suspend fun setBlockDohBypass(enabled: Boolean) = dns.setBlockDohBypass(enabled)
    suspend fun getBlockDohBypassSnapshot(): Boolean = dns.getBlockDohBypassSnapshot()
    suspend fun setBlockWebRtcLeak(enabled: Boolean) = dns.setBlockWebRtcLeak(enabled)
    suspend fun getBlockWebRtcLeakSnapshot(): Boolean = dns.getBlockWebRtcLeakSnapshot()

    suspend fun setThemeMode(mode: String) = appearance.setThemeMode(mode)
    suspend fun setAppLanguage(language: String) = appearance.setAppLanguage(language)
    suspend fun setAccentColor(color: String) = appearance.setAccentColor(color)
    suspend fun setShowBottomNavLabels(show: Boolean) = appearance.setShowBottomNavLabels(show)

    suspend fun setFilterUrl(url: String) = filter.setFilterUrl(url)
    suspend fun setAutoUpdateEnabled(enabled: Boolean) = filter.setAutoUpdateEnabled(enabled)
    suspend fun setAutoUpdateFrequency(frequency: String) = filter.setAutoUpdateFrequency(frequency)
    suspend fun setAutoUpdateWifiOnly(wifiOnly: Boolean) = filter.setAutoUpdateWifiOnly(wifiOnly)
    suspend fun setAutoUpdateNotification(type: String) = filter.setAutoUpdateNotification(type)
    suspend fun setProtectionLevel(level: String) = filter.setProtectionLevel(level)
    suspend fun setSafeSearchEnabled(enabled: Boolean) = filter.setSafeSearchEnabled(enabled)
    suspend fun setYoutubeRestrictedMode(enabled: Boolean) = filter.setYoutubeRestrictedMode(enabled)
    suspend fun setGeoIpCustomUrl(url: String) = filter.setGeoIpCustomUrl(url)
    suspend fun setGeoIpAutoUpdate(enabled: Boolean) = filter.setGeoIpAutoUpdate(enabled)
    suspend fun setFilterBuildMode(mode: String) = filter.setFilterBuildMode(mode)
    suspend fun getFilterBuildModeSnapshot(): String = filter.getFilterBuildModeSnapshot()

    suspend fun setRoutingMode(mode: String) {
        wireguard.setRoutingMode(mode)
        directBootPrefs.routingMode = mode
    }
    suspend fun getRoutingModeSnapshot(): String = wireguard.getRoutingModeSnapshot()
    suspend fun getWgConfigJsonSnapshot(): String? = wireguard.getWgConfigJsonSnapshot()
    suspend fun getWgProfilesSnapshot(): List<WireGuardProfile> = wireguard.getWgProfilesSnapshot()
    suspend fun getActiveWgProfileSnapshot(): WireGuardProfile? = wireguard.getActiveWgProfileSnapshot()
    suspend fun addOrUpdateWgProfile(profile: WireGuardProfile, makeActive: Boolean = false) =
        wireguard.addOrUpdateWgProfile(profile, makeActive)
    suspend fun removeWgProfile(id: String) = wireguard.removeWgProfile(id)
    suspend fun setActiveWgProfile(id: String) = wireguard.setActiveWgProfile(id)
    suspend fun renameWgProfile(id: String, name: String) = wireguard.renameWgProfile(id, name)
    suspend fun clearAllWgProfiles() = wireguard.clearAllWgProfiles()
    suspend fun migrateLegacyWgConfigIfNeeded() = wireguard.migrateLegacyWgConfigIfNeeded()
    suspend fun setExcludeLan(enabled: Boolean) = wireguard.setExcludeLan(enabled)
    suspend fun setAllowAppBypass(enabled: Boolean) = wireguard.setAllowAppBypass(enabled)

    val lastSubscriptionUrl: Flow<String?> get() = shadowsocks.lastSubscriptionUrl
    val lastSubscriptionUpdated: Flow<Long?> get() = shadowsocks.lastSubscriptionUpdated
    val proxySubscriptions: Flow<List<ProxySubscription>> get() = shadowsocks.subscriptions
    suspend fun setLastSubscriptionUrl(url: String) = shadowsocks.setLastSubscriptionUrl(url)
    suspend fun getLastSubscriptionUrlSnapshot(): String? = shadowsocks.getLastSubscriptionUrlSnapshot()
    suspend fun removeDuplicateWgProfiles(): Int = wireguard.removeDuplicateWgProfiles()
    suspend fun getProxySubscriptionsSnapshot(): List<ProxySubscription> = proxySubscriptions.first()
    suspend fun addOrUpdateSubscription(subscription: ProxySubscription) = shadowsocks.addOrUpdateSubscription(subscription)
    suspend fun removeSubscription(id: String, deleteNodes: Boolean = true) = shadowsocks.removeSubscription(id, deleteNodes)
    suspend fun syncSubscriptionProfiles(subscription: ProxySubscription, newProfiles: List<ProxyProfile>) =
        shadowsocks.syncSubscriptionProfiles(subscription, newProfiles)
    suspend fun removeDuplicateProfiles(): Int = shadowsocks.removeDuplicateProfiles()

    val activeProxyType: Flow<String> get() = shadowsocks.activeProxyType
    val proxyProfiles: Flow<List<ProxyProfile>> get() = shadowsocks.proxyProfiles
    val activeProxyProfileId: Flow<String?> get() = shadowsocks.activeProxyProfileId
    val activeProxyProfile: Flow<ProxyProfile?> get() = shadowsocks.activeProxyProfile

    suspend fun getActiveProxyProfileSnapshot(): ProxyProfile? = shadowsocks.getActiveProxyProfileSnapshot()
    suspend fun setActiveProxyProfileId(id: String) = shadowsocks.setActiveProxyProfileId(id)
    suspend fun addProxyProfile(profile: ProxyProfile) = shadowsocks.addProxyProfile(profile)
    suspend fun updateProxyProfile(profile: ProxyProfile) = shadowsocks.updateProxyProfile(profile)
    suspend fun getProxyProfile(id: String): ProxyProfile? = shadowsocks.getProxyProfile(id)
    suspend fun addProxyProfiles(profiles: List<ProxyProfile>) = shadowsocks.addProxyProfiles(profiles)
    suspend fun deleteProxyProfile(id: String) = shadowsocks.deleteProxyProfile(id)
    suspend fun renameProxyProfile(id: String, newName: String) = shadowsocks.renameProxyProfile(id, newName)

    val ssProfiles: Flow<List<ShadowsocksProfile>> get() = shadowsocks.ssProfiles
    val activeSsProfileId: Flow<String?> get() = shadowsocks.activeSsProfileId
    val ssProfile: Flow<ShadowsocksProfile?> get() = shadowsocks.ssProfile

    suspend fun getSsProfileSnapshot(): ShadowsocksProfile? = shadowsocks.getSsProfileSnapshot()
    suspend fun getActiveProxyTypeSnapshot(): String = shadowsocks.getActiveProxyTypeSnapshot()
    suspend fun setActiveProxyType(type: String) = shadowsocks.setActiveProxyType(type)
    suspend fun setActiveSsProfileId(id: String) = shadowsocks.setActiveProfileId(id)
    suspend fun addSsProfile(profile: ShadowsocksProfile) = shadowsocks.addProfile(profile)
    suspend fun setSsProfile(profile: ShadowsocksProfile) = shadowsocks.addProfile(profile)
    suspend fun deleteSsProfile(id: String) = shadowsocks.deleteProfile(id)
    suspend fun renameSsProfile(id: String, newName: String) = shadowsocks.renameProfile(id, newName)

    val hasRatedApp: Flow<Boolean> get() = vpnSecurity.hasRatedApp
    suspend fun setHasRatedApp(rated: Boolean) = vpnSecurity.setHasRatedApp(rated)

    val ratePromptLastDismissed: Flow<Long> get() = vpnSecurity.ratePromptLastDismissed
    suspend fun setRatePromptLastDismissed(timestamp: Long) = vpnSecurity.setRatePromptLastDismissed(timestamp)

    suspend fun resetSettings() {
        dataStore.edit { prefs ->
            DnsPreferences.reset(prefs)
            AppearancePreferences.reset(prefs)
            FilterPreferences.reset(prefs)
            WireGuardPreferences.reset(prefs)
            VpnSecurityPreferences.reset(prefs)
        }
        directBootPrefs.autoReconnect = true
        directBootPrefs.routingMode = ROUTING_MODE_DIRECT
    }
}
