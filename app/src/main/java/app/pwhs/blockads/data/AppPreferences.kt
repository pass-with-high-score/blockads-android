package app.pwhs.blockads.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "blockads_prefs")

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_FILTER_URL = stringPreferencesKey("filter_url")
        private val KEY_UPSTREAM_DNS = stringPreferencesKey("upstream_dns")
        private val KEY_FALLBACK_DNS = stringPreferencesKey("fallback_dns")
        private val KEY_DNS_PROTOCOL = stringPreferencesKey("dns_protocol")
        private val KEY_DOH_URL = stringPreferencesKey("doh_url")
        private val KEY_DNS_PROVIDER_ID = stringPreferencesKey("dns_provider_id")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_WHITELISTED_APPS = stringSetPreferencesKey("whitelisted_apps")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        private val KEY_AUTO_UPDATE_ENABLED = booleanPreferencesKey("auto_update_enabled")
        private val KEY_AUTO_UPDATE_FREQUENCY = stringPreferencesKey("auto_update_frequency")
        private val KEY_AUTO_UPDATE_WIFI_ONLY = booleanPreferencesKey("auto_update_wifi_only")
        private val KEY_AUTO_UPDATE_NOTIFICATION = stringPreferencesKey("auto_update_notification")
        private val KEY_DNS_RESPONSE_TYPE = stringPreferencesKey("dns_response_type")
        private val KEY_PROTECTION_LEVEL = stringPreferencesKey("protection_level")
        private val KEY_SAFE_SEARCH_ENABLED = booleanPreferencesKey("safe_search_enabled")
        private val KEY_YOUTUBE_RESTRICTED_MODE = booleanPreferencesKey("youtube_restricted_mode")
        private val KEY_DAILY_SUMMARY_ENABLED = booleanPreferencesKey("daily_summary_enabled")
        private val KEY_MILESTONE_NOTIFICATIONS_ENABLED =
            booleanPreferencesKey("milestone_notifications_enabled")
        private val KEY_LAST_MILESTONE_BLOCKED = longPreferencesKey("last_milestone_blocked")
        private val KEY_ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        private val KEY_HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        private val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val KEY_FIREWALL_ENABLED = booleanPreferencesKey("firewall_enabled")

        const val PROTECTION_BASIC = "BASIC"
        const val PROTECTION_STANDARD = "STANDARD"
        const val PROTECTION_STRICT = "STRICT"

        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"

        const val ACCENT_GREEN = "green"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_PURPLE = "purple"
        const val ACCENT_ORANGE = "orange"
        const val ACCENT_PINK = "pink"
        const val ACCENT_TEAL = "teal"
        const val ACCENT_DYNAMIC = "dynamic"

        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_VI = "vi"
        const val LANGUAGE_JA = "ja"
        const val LANGUAGE_KO = "ko"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_TH = "th"
        const val LANGUAGE_ES = "es"

        const val UPDATE_FREQUENCY_6H = "6h"
        const val UPDATE_FREQUENCY_12H = "12h"
        const val UPDATE_FREQUENCY_24H = "24h"
        const val UPDATE_FREQUENCY_48H = "48h"
        const val UPDATE_FREQUENCY_MANUAL = "manual"

        const val NOTIFICATION_SILENT = "silent"
        const val NOTIFICATION_NORMAL = "normal"
        const val NOTIFICATION_NONE = "none"

        const val DNS_RESPONSE_NXDOMAIN = "nxdomain"
        const val DNS_RESPONSE_REFUSED = "refused"
        const val DNS_RESPONSE_CUSTOM_IP = "custom_ip"

        const val DEFAULT_FILTER_URL = "https://abpvn.com/android/abpvn.txt"
        const val DEFAULT_UPSTREAM_DNS = "8.8.8.8"
        const val DEFAULT_FALLBACK_DNS = "1.1.1.1"
        const val DEFAULT_DNS_PROTOCOL = "PLAIN"
        const val DEFAULT_DOH_URL = "https://dns.google/dns-query"
    }

    val vpnEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VPN_ENABLED] ?: false
    }

    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_RECONNECT] ?: true
    }

    val filterUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FILTER_URL] ?: DEFAULT_FILTER_URL
    }

    val upstreamDns: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_UPSTREAM_DNS] ?: DEFAULT_UPSTREAM_DNS
    }

    val fallbackDns: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FALLBACK_DNS] ?: DEFAULT_FALLBACK_DNS
    }

    val dnsProtocol: Flow<DnsProtocol> = context.dataStore.data.map { prefs ->
        val protocolString = prefs[KEY_DNS_PROTOCOL] ?: DEFAULT_DNS_PROTOCOL
        try {
            DnsProtocol.valueOf(protocolString)
        } catch (e: IllegalArgumentException) {
            DnsProtocol.PLAIN
        }
    }

    val dohUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOH_URL] ?: DEFAULT_DOH_URL
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val whitelistedApps: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_WHITELISTED_APPS] ?: emptySet()
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: THEME_SYSTEM
    }

    val appLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_APP_LANGUAGE] ?: LANGUAGE_SYSTEM
    }

    val autoUpdateEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_ENABLED] ?: true
    }

    val autoUpdateFrequency: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_FREQUENCY] ?: UPDATE_FREQUENCY_24H
    }

    val autoUpdateWifiOnly: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_WIFI_ONLY] ?: true
    }

    val autoUpdateNotification: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_NOTIFICATION] ?: NOTIFICATION_NORMAL
    }

    val dnsProviderId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_DNS_PROVIDER_ID]
    }

    val dnsResponseType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DNS_RESPONSE_TYPE] ?: DNS_RESPONSE_CUSTOM_IP
    }

    val protectionLevel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PROTECTION_LEVEL] ?: PROTECTION_STANDARD
    }

    val safeSearchEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SAFE_SEARCH_ENABLED] ?: false
    }

    val youtubeRestrictedMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_YOUTUBE_RESTRICTED_MODE] ?: false
    }

    val dailySummaryEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DAILY_SUMMARY_ENABLED] ?: true
    }

    val milestoneNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_MILESTONE_NOTIFICATIONS_ENABLED] ?: true
    }

    val lastMilestoneBlocked: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_MILESTONE_BLOCKED] ?: 0L
    }

    val activeProfileId: Flow<Long> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_ACTIVE_PROFILE_ID] ?: -1L
        }

    val highContrast: Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_HIGH_CONTRAST] ?: false
        }

    val accentColor: Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_ACCENT_COLOR] ?: ACCENT_GREEN
        }

    val firewallEnabled: Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_FIREWALL_ENABLED] ?: false
        }


    suspend fun setVpnEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VPN_ENABLED] = enabled
        }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_RECONNECT] = enabled
        }
    }

    suspend fun setFilterUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILTER_URL] = url
        }
    }

    suspend fun setUpstreamDns(dns: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_UPSTREAM_DNS] = dns
        }
    }

    suspend fun setFallbackDns(dns: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FALLBACK_DNS] = dns
        }
    }

    suspend fun setDnsProtocol(protocol: DnsProtocol) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DNS_PROTOCOL] = protocol.name
        }
    }

    suspend fun setDohUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DOH_URL] = url
        }
    }

    suspend fun setDnsProviderId(providerId: String?) {
        context.dataStore.edit { prefs ->
            if (providerId == null) {
                prefs.remove(KEY_DNS_PROVIDER_ID)
            } else {
                prefs[KEY_DNS_PROVIDER_ID] = providerId
            }
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setWhitelistedApps(apps: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WHITELISTED_APPS] = apps
        }
    }

    suspend fun toggleWhitelistedApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_WHITELISTED_APPS] ?: emptySet()
            prefs[KEY_WHITELISTED_APPS] = if (packageName in current) {
                current - packageName
            } else {
                current + packageName
            }
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
    }

    suspend fun setAppLanguage(language: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_LANGUAGE] = language
        }
    }

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_ENABLED] = enabled
        }
    }

    suspend fun setAutoUpdateFrequency(frequency: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_FREQUENCY] = frequency
        }
    }

    suspend fun setAutoUpdateWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun setAutoUpdateNotification(notificationType: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_NOTIFICATION] = notificationType
        }
    }

    suspend fun setDnsResponseType(responseType: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DNS_RESPONSE_TYPE] = responseType
        }
    }

    suspend fun setProtectionLevel(level: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PROTECTION_LEVEL] = level
        }
    }

    suspend fun setSafeSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SAFE_SEARCH_ENABLED] = enabled
        }
    }

    suspend fun setYoutubeRestrictedMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_YOUTUBE_RESTRICTED_MODE] = enabled
        }
    }

    suspend fun setDailySummaryEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DAILY_SUMMARY_ENABLED] = enabled
        }
    }

    suspend fun setMilestoneNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MILESTONE_NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun setLastMilestoneBlocked(count: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_MILESTONE_BLOCKED] = count
        }
    }

    suspend fun setActiveProfileId(id: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun setHighContrast(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HIGH_CONTRAST] = enabled
        }
    }

    suspend fun setAccentColor(color: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCENT_COLOR] = color
        }
    }

    suspend fun setFirewallEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FIREWALL_ENABLED] = enabled
        }
    }

    suspend fun getWhitelistedAppsSnapshot(): Set<String> {
        return whitelistedApps.first()
    }
}
