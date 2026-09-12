package app.pwhs.blockads.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.pwhs.blockads.data.datastore.prefs.AppearancePreferences
import app.pwhs.blockads.data.datastore.prefs.DnsPreferences
import app.pwhs.blockads.data.datastore.prefs.FilterPreferences
import app.pwhs.blockads.data.datastore.prefs.VpnSecurityPreferences
import app.pwhs.blockads.data.datastore.prefs.WireGuardPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.entities.WireGuardProfile
import kotlinx.coroutines.flow.Flow

val Context.blockAdsDataStore: DataStore<Preferences> by preferencesDataStore(name = "blockads_prefs")

class AppPreferences(context: Context) {

    private val dataStore = context.blockAdsDataStore
    val dns = DnsPreferences(dataStore)
    val appearance = AppearancePreferences(dataStore)
    val filter = FilterPreferences(dataStore)
    val wireguard = WireGuardPreferences(dataStore)
    val vpnSecurity = VpnSecurityPreferences(dataStore)

    companion object {
        const val ROUTING_MODE_DIRECT = WireGuardPreferences.ROUTING_MODE_DIRECT
        const val ROUTING_MODE_WIREGUARD = WireGuardPreferences.ROUTING_MODE_WIREGUARD
        const val ROUTING_MODE_ROOT = WireGuardPreferences.ROUTING_MODE_ROOT

        const val PROTECTION_BASIC = FilterPreferences.PROTECTION_BASIC
        const val PROTECTION_STANDARD = FilterPreferences.PROTECTION_STANDARD
        const val PROTECTION_STRICT = FilterPreferences.PROTECTION_STRICT

        const val THEME_SYSTEM = AppearancePreferences.THEME_SYSTEM
        const val THEME_DARK = AppearancePreferences.THEME_DARK
        const val THEME_LIGHT = AppearancePreferences.THEME_LIGHT

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
    val networkSwitchDelayEnabled: Flow<Boolean> get() = vpnSecurity.networkSwitchDelayEnabled
    val networkSwitchDelaySec: Flow<Int> get() = vpnSecurity.networkSwitchDelaySec
    val onboardingCompleted: Flow<Boolean> get() = vpnSecurity.onboardingCompleted
    val whitelistedApps: Flow<Set<String>> get() = vpnSecurity.whitelistedApps
    val dailySummaryEnabled: Flow<Boolean> get() = vpnSecurity.dailySummaryEnabled
    val milestoneNotificationsEnabled: Flow<Boolean> get() = vpnSecurity.milestoneNotificationsEnabled
    val lastMilestoneBlocked: Flow<Long> get() = vpnSecurity.lastMilestoneBlocked
    val activeProfileId: Flow<Long> get() = vpnSecurity.activeProfileId
    val recordDnsLogs: Flow<Boolean> get() = vpnSecurity.recordDnsLogs
    val firewallEnabled: Flow<Boolean> get() = vpnSecurity.firewallEnabled
    val crashReportingEnabled: Flow<Boolean> get() = vpnSecurity.crashReportingEnabled
    val hideFromRecents: Flow<Boolean> get() = vpnSecurity.hideFromRecents
    val trustedSsids: Flow<Set<String>> get() = vpnSecurity.trustedSsids
    val pauseOnTrustedEnabled: Flow<Boolean> get() = vpnSecurity.pauseOnTrustedEnabled
    val pausedByTrusted: Flow<Boolean> get() = vpnSecurity.pausedByTrusted
    val pausedTrustedSsid: Flow<String> get() = vpnSecurity.pausedTrustedSsid
    val filterHttp3: Flow<Boolean> get() = vpnSecurity.filterHttp3

    // ── DNS Flows ────────────────────────────────────────────────────────
    val upstreamDns: Flow<String> get() = dns.upstreamDns
    val fallbackDns: Flow<String> get() = dns.fallbackDns
    val dnsProtocol: Flow<DnsProtocol> get() = dns.dnsProtocol
    val dohUrl: Flow<String> get() = dns.dohUrl
    val dnsProviderId: Flow<String?> get() = dns.dnsProviderId
    val dnsResponseType: Flow<String> get() = dns.dnsResponseType
    val splitDnsZones: Flow<String> get() = dns.splitDnsZones
    val blockDohBypass: Flow<Boolean> get() = dns.blockDohBypass

    // ── Appearance Flows ─────────────────────────────────────────────────
    val themeMode: Flow<String> get() = appearance.themeMode
    val appLanguage: Flow<String> get() = appearance.appLanguage
    val accentColor: Flow<String> get() = appearance.accentColor
    val showBottomNavLabels: Flow<Boolean> get() = appearance.showBottomNavLabels

    // ── Filter Flows ─────────────────────────────────────────────────────
    val filterUrl: Flow<String> get() = filter.filterUrl
    val autoUpdateEnabled: Flow<Boolean> get() = filter.autoUpdateEnabled
    val autoUpdateFrequency: Flow<String> get() = filter.autoUpdateFrequency
    val autoUpdateWifiOnly: Flow<Boolean> get() = filter.autoUpdateWifiOnly
    val autoUpdateNotification: Flow<String> get() = filter.autoUpdateNotification
    val protectionLevel: Flow<String> get() = filter.protectionLevel
    val safeSearchEnabled: Flow<Boolean> get() = filter.safeSearchEnabled
    val youtubeRestrictedMode: Flow<Boolean> get() = filter.youtubeRestrictedMode

    // ── WireGuard Flows ──────────────────────────────────────────────────
    val routingMode: Flow<String> get() = wireguard.routingMode
    val wgProfiles: Flow<List<WireGuardProfile>> get() = wireguard.wgProfiles
    val wgActiveProfileId: Flow<String?> get() = wireguard.wgActiveProfileId
    val excludeLan: Flow<Boolean> get() = wireguard.excludeLan

    // ── Mutator & Snapshot Delegates ─────────────────────────────────────
    suspend fun setVpnEnabled(enabled: Boolean) = vpnSecurity.setVpnEnabled(enabled)
    suspend fun setAutoReconnect(enabled: Boolean) = vpnSecurity.setAutoReconnect(enabled)
    suspend fun setNetworkSwitchDelayEnabled(enabled: Boolean) = vpnSecurity.setNetworkSwitchDelayEnabled(enabled)
    suspend fun setNetworkSwitchDelaySec(seconds: Int) = vpnSecurity.setNetworkSwitchDelaySec(seconds)
    suspend fun setOnboardingCompleted(completed: Boolean) = vpnSecurity.setOnboardingCompleted(completed)
    suspend fun setWhitelistedApps(apps: Set<String>) = vpnSecurity.setWhitelistedApps(apps)
    suspend fun toggleWhitelistedApp(packageName: String) = vpnSecurity.toggleWhitelistedApp(packageName)
    suspend fun getWhitelistedAppsSnapshot(): Set<String> = vpnSecurity.getWhitelistedAppsSnapshot()
    suspend fun setDailySummaryEnabled(enabled: Boolean) = vpnSecurity.setDailySummaryEnabled(enabled)
    suspend fun setMilestoneNotificationsEnabled(enabled: Boolean) = vpnSecurity.setMilestoneNotificationsEnabled(enabled)
    suspend fun setLastMilestoneBlocked(count: Long) = vpnSecurity.setLastMilestoneBlocked(count)
    suspend fun setActiveProfileId(id: Long) = vpnSecurity.setActiveProfileId(id)
    suspend fun setRecordDnsLogs(enabled: Boolean) = vpnSecurity.setRecordDnsLogs(enabled)
    suspend fun setFirewallEnabled(enabled: Boolean) = vpnSecurity.setFirewallEnabled(enabled)
    suspend fun setHttpsFilteringEnabled(enabled: Boolean) = vpnSecurity.setHttpsFilteringEnabled(enabled)
    suspend fun getHttpsFilteringEnabledSnapshot(): Boolean = vpnSecurity.getHttpsFilteringEnabledSnapshot()
    suspend fun setFilterHttp3(enabled: Boolean) = vpnSecurity.setFilterHttp3(enabled)
    suspend fun getFilterHttp3Snapshot(): Boolean = vpnSecurity.getFilterHttp3Snapshot()
    suspend fun setSelectedBrowsers(packages: Set<String>) = vpnSecurity.setSelectedBrowsers(packages)
    fun getSelectedBrowsersSnapshot(): Set<String> = vpnSecurity.getSelectedBrowsersSnapshot()
    suspend fun setCrashReportingEnabled(enabled: Boolean) = vpnSecurity.setCrashReportingEnabled(enabled)
    suspend fun setHideFromRecents(enabled: Boolean) = vpnSecurity.setHideFromRecents(enabled)
    suspend fun setTrustedSsids(ssids: Set<String>) = vpnSecurity.setTrustedSsids(ssids)
    suspend fun toggleTrustedSsid(ssid: String) = vpnSecurity.toggleTrustedSsid(ssid)
    suspend fun setPauseOnTrustedEnabled(enabled: Boolean) = vpnSecurity.setPauseOnTrustedEnabled(enabled)
    suspend fun getTrustedSsidsSnapshot(): Set<String> = vpnSecurity.getTrustedSsidsSnapshot()
    suspend fun getPauseOnTrustedEnabledSnapshot(): Boolean = vpnSecurity.getPauseOnTrustedEnabledSnapshot()
    suspend fun setPausedByTrusted(value: Boolean, ssid: String = "") = vpnSecurity.setPausedByTrusted(value, ssid)
    suspend fun getPausedByTrustedSnapshot(): Boolean = vpnSecurity.getPausedByTrustedSnapshot()

    suspend fun setUpstreamDns(dnsServer: String) = dns.setUpstreamDns(dnsServer)
    suspend fun setFallbackDns(dnsServer: String) = dns.setFallbackDns(dnsServer)
    suspend fun setDnsProtocol(protocol: DnsProtocol) = dns.setDnsProtocol(protocol)
    suspend fun setDohUrl(url: String) = dns.setDohUrl(url)
    suspend fun setDnsProviderId(providerId: String?) = dns.setDnsProviderId(providerId)
    suspend fun setDnsResponseType(responseType: String) = dns.setDnsResponseType(responseType)
    suspend fun setSplitDnsZones(zones: String) = dns.setSplitDnsZones(zones)
    suspend fun setBlockDohBypass(enabled: Boolean) = dns.setBlockDohBypass(enabled)
    suspend fun getBlockDohBypassSnapshot(): Boolean = dns.getBlockDohBypassSnapshot()

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

    suspend fun setRoutingMode(mode: String) = wireguard.setRoutingMode(mode)
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
}
