package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

class VpnSecurityPreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        val KEY_VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
        val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_WHITELISTED_APPS = stringSetPreferencesKey("whitelisted_apps")
        val KEY_DAILY_SUMMARY_ENABLED = booleanPreferencesKey("daily_summary_enabled")
        val KEY_MILESTONE_NOTIFICATIONS_ENABLED =
            booleanPreferencesKey("milestone_notifications_enabled")
        val KEY_LAST_MILESTONE_BLOCKED = longPreferencesKey("last_milestone_blocked")
        val KEY_LAST_SEEN_MILESTONE_DIALOG = longPreferencesKey("last_seen_milestone_dialog")
        val KEY_ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val KEY_RECORD_DNS_LOGS = booleanPreferencesKey("record_dns_logs")
        val KEY_FIREWALL_ENABLED = booleanPreferencesKey("firewall_enabled")
        val KEY_HTTPS_FILTERING_ENABLED = booleanPreferencesKey("https_filtering_enabled")
        val KEY_FILTER_HTTP3 = booleanPreferencesKey("filter_http3")
        val KEY_SELECTED_BROWSERS = stringSetPreferencesKey("selected_browsers")
        val KEY_CRASH_REPORTING_ENABLED = booleanPreferencesKey("crash_reporting_enabled")
        val KEY_HIDE_FROM_RECENTS = booleanPreferencesKey("hide_from_recents")
        val KEY_TRUSTED_SSIDS = stringSetPreferencesKey("trusted_ssids")
        val KEY_PAUSE_ON_TRUSTED = booleanPreferencesKey("pause_on_trusted")
        val KEY_USE_SYSTEM_DNS_ON_TRUSTED = booleanPreferencesKey("use_system_dns_on_trusted")
        val KEY_PAUSED_BY_TRUSTED = booleanPreferencesKey("paused_by_trusted")
        val KEY_PAUSED_TRUSTED_SSID = stringPreferencesKey("paused_trusted_ssid")
        val KEY_VPN_REVOKED_BY_ANOTHER_APP = booleanPreferencesKey("vpn_revoked_by_another_app")
        val KEY_STABILITY_TIP_DISMISSED = booleanPreferencesKey("stability_tip_dismissed")
        val KEY_STABILITY_TIP_DISMISSED_AT = longPreferencesKey("stability_tip_dismissed_at")
        val KEY_STABILITY_TIP_LAST_BATTERY_IGNORED =
            booleanPreferencesKey("stability_tip_last_battery_ignored")
        val KEY_STABILITY_TIP_LAST_ALWAYS_ON =
            booleanPreferencesKey("stability_tip_last_always_on")
        val KEY_DISMISSED_FEATURE_TIPS = stringSetPreferencesKey("dismissed_feature_tips")
        val KEY_PREFERRED_IP_TYPE = stringPreferencesKey("preferred_ip_type")
        val KEY_HAS_RATED_APP = booleanPreferencesKey("has_rated_app")
        val KEY_RATE_PROMPT_LAST_DISMISSED = longPreferencesKey("rate_prompt_last_dismissed")

        const val PREFERRED_IP_AUTO = "auto"
        const val PREFERRED_IP_IPV4 = "ipv4"
        const val PREFERRED_IP_IPV6 = "ipv6"

        fun reset(prefs: MutablePreferences) {
            prefs.remove(KEY_AUTO_RECONNECT)
            prefs.remove(KEY_WHITELISTED_APPS)
            prefs.remove(KEY_DAILY_SUMMARY_ENABLED)
            prefs.remove(KEY_MILESTONE_NOTIFICATIONS_ENABLED)
            prefs.remove(KEY_RECORD_DNS_LOGS)
            prefs.remove(KEY_FIREWALL_ENABLED)
            prefs.remove(KEY_HTTPS_FILTERING_ENABLED)
            prefs.remove(KEY_FILTER_HTTP3)
            prefs.remove(KEY_SELECTED_BROWSERS)
            prefs.remove(KEY_CRASH_REPORTING_ENABLED)
            prefs.remove(KEY_HIDE_FROM_RECENTS)
            prefs.remove(KEY_TRUSTED_SSIDS)
            prefs.remove(KEY_PAUSE_ON_TRUSTED)
            prefs.remove(KEY_USE_SYSTEM_DNS_ON_TRUSTED)
            prefs.remove(KEY_PAUSED_BY_TRUSTED)
            prefs.remove(KEY_PAUSED_TRUSTED_SSID)
            prefs.remove(KEY_PREFERRED_IP_TYPE)
        }
    }

    val stabilityTipDismissed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_STABILITY_TIP_DISMISSED] ?: false
    }

    val stabilityTipDismissedAt: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_STABILITY_TIP_DISMISSED_AT] ?: 0L
    }

    val stabilityTipLastBatteryIgnored: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_STABILITY_TIP_LAST_BATTERY_IGNORED] ?: false
    }

    val stabilityTipLastAlwaysOn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_STABILITY_TIP_LAST_ALWAYS_ON] ?: false
    }

    val dismissedFeatureTips: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_DISMISSED_FEATURE_TIPS] ?: emptySet()
    }

    val vpnRevokedByAnotherApp: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VPN_REVOKED_BY_ANOTHER_APP] ?: false
    }

    val vpnEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VPN_ENABLED] ?: false
    }

    val autoReconnect: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_RECONNECT] ?: true
    }


    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val whitelistedApps: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_WHITELISTED_APPS] ?: emptySet()
    }

    val dailySummaryEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DAILY_SUMMARY_ENABLED] ?: false
    }

    val milestoneNotificationsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_MILESTONE_NOTIFICATIONS_ENABLED] ?: false
    }

    val lastMilestoneBlocked: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_MILESTONE_BLOCKED] ?: 0L
    }

    val lastSeenMilestoneDialog: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SEEN_MILESTONE_DIALOG] ?: 0L
    }

    val activeProfileId: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_PROFILE_ID] ?: -1L
    }

    val recordDnsLogs: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_RECORD_DNS_LOGS] ?: true
    }

    val firewallEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FIREWALL_ENABLED] ?: false
    }

    val httpsFilteringEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HTTPS_FILTERING_ENABLED] ?: false
    }

    val filterHttp3: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FILTER_HTTP3] ?: true
    }

    val crashReportingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_CRASH_REPORTING_ENABLED] ?: false
    }

    val hideFromRecents: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HIDE_FROM_RECENTS] ?: false
    }

    val trustedSsids: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_TRUSTED_SSIDS] ?: emptySet()
    }

    val pauseOnTrustedEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PAUSE_ON_TRUSTED] ?: false
    }

    val pausedByTrusted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PAUSED_BY_TRUSTED] ?: false
    }

    val pausedTrustedSsid: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PAUSED_TRUSTED_SSID] ?: ""
    }

    val useSystemDnsOnTrustedEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_USE_SYSTEM_DNS_ON_TRUSTED] ?: false
    }

    suspend fun setVpnEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_VPN_ENABLED] = enabled }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_AUTO_RECONNECT] = enabled }
    }


    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setWhitelistedApps(apps: Set<String>) {
        dataStore.edit { prefs -> prefs[KEY_WHITELISTED_APPS] = apps }
    }

    suspend fun toggleWhitelistedApp(packageName: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_WHITELISTED_APPS] ?: emptySet()
            prefs[KEY_WHITELISTED_APPS] = if (packageName in current) {
                current - packageName
            } else {
                current + packageName
            }
        }
    }

    suspend fun getWhitelistedAppsSnapshot(): Set<String> = whitelistedApps.first()

    suspend fun setDailySummaryEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_DAILY_SUMMARY_ENABLED] = enabled }
    }

    suspend fun setMilestoneNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_MILESTONE_NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setLastMilestoneBlocked(count: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_MILESTONE_BLOCKED] = count }
    }

    suspend fun setLastSeenMilestoneDialog(milestone: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_SEEN_MILESTONE_DIALOG] = milestone }
    }

    suspend fun setActiveProfileId(id: Long) {
        dataStore.edit { prefs -> prefs[KEY_ACTIVE_PROFILE_ID] = id }
    }

    suspend fun setRecordDnsLogs(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_RECORD_DNS_LOGS] = enabled }
    }

    suspend fun setFirewallEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_FIREWALL_ENABLED] = enabled }
    }

    suspend fun setHttpsFilteringEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_HTTPS_FILTERING_ENABLED] = enabled }
    }

    suspend fun getHttpsFilteringEnabledSnapshot(): Boolean =
        dataStore.data.first()[KEY_HTTPS_FILTERING_ENABLED] ?: false

    suspend fun setFilterHttp3(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_FILTER_HTTP3] = enabled }
    }

    suspend fun getFilterHttp3Snapshot(): Boolean =
        dataStore.data.first()[KEY_FILTER_HTTP3] ?: true

    suspend fun setSelectedBrowsers(packages: Set<String>) {
        dataStore.edit { prefs -> prefs[KEY_SELECTED_BROWSERS] = packages }
    }

    fun getSelectedBrowsersSnapshot(): Set<String> = getSelectedBrowsersOrNullSnapshot() ?: emptySet()

    fun getSelectedBrowsersOrNullSnapshot(): Set<String>? {
        return try {
            runBlocking {
                dataStore.data.first()[KEY_SELECTED_BROWSERS]
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun setCrashReportingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_CRASH_REPORTING_ENABLED] = enabled }
    }

    suspend fun setHideFromRecents(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_HIDE_FROM_RECENTS] = enabled }
    }

    suspend fun setTrustedSsids(ssids: Set<String>) {
        dataStore.edit { it[KEY_TRUSTED_SSIDS] = ssids }
    }

    suspend fun toggleTrustedSsid(ssid: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_SSIDS] ?: emptySet()
            prefs[KEY_TRUSTED_SSIDS] = if (ssid in current) current - ssid else current + ssid
        }
    }

    suspend fun setPauseOnTrustedEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PAUSE_ON_TRUSTED] = enabled }
    }

    suspend fun setUseSystemDnsOnTrustedEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_USE_SYSTEM_DNS_ON_TRUSTED] = enabled }
    }

    suspend fun getUseSystemDnsOnTrustedEnabledSnapshot(): Boolean =
        dataStore.data.map { it[KEY_USE_SYSTEM_DNS_ON_TRUSTED] ?: false }.first()

    suspend fun getTrustedSsidsSnapshot(): Set<String> =
        dataStore.data.map { it[KEY_TRUSTED_SSIDS] ?: emptySet() }.first()

    suspend fun getPauseOnTrustedEnabledSnapshot(): Boolean =
        dataStore.data.map { it[KEY_PAUSE_ON_TRUSTED] ?: false }.first()

    suspend fun setPausedByTrusted(value: Boolean, ssid: String = "") {
        dataStore.edit {
            it[KEY_PAUSED_BY_TRUSTED] = value
            it[KEY_PAUSED_TRUSTED_SSID] = if (value) ssid else ""
        }
    }

    suspend fun getPausedByTrustedSnapshot(): Boolean =
        dataStore.data.map { it[KEY_PAUSED_BY_TRUSTED] ?: false }.first()

    suspend fun setVpnRevokedByAnotherApp(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_VPN_REVOKED_BY_ANOTHER_APP] = value
        }
    }

    suspend fun setStabilityTipDismissed(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_STABILITY_TIP_DISMISSED] = value
            if (value) {
                prefs[KEY_STABILITY_TIP_DISMISSED_AT] = System.currentTimeMillis()
            } else {
                prefs[KEY_STABILITY_TIP_DISMISSED_AT] = 0L
            }
        }
    }

    suspend fun recordStabilityTipDismissed(batteryIgnored: Boolean, alwaysOn: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_STABILITY_TIP_DISMISSED_AT] = System.currentTimeMillis()
            prefs[KEY_STABILITY_TIP_LAST_BATTERY_IGNORED] = batteryIgnored
            prefs[KEY_STABILITY_TIP_LAST_ALWAYS_ON] = alwaysOn
            prefs[KEY_STABILITY_TIP_DISMISSED] = true
        }
    }

    suspend fun updateStabilityTipHardwareState(batteryIgnored: Boolean, alwaysOn: Boolean) {
        dataStore.edit { prefs ->
            val lastBattery = prefs[KEY_STABILITY_TIP_LAST_BATTERY_IGNORED]
            val lastAlwaysOn = prefs[KEY_STABILITY_TIP_LAST_ALWAYS_ON]

            if ((lastBattery == true && !batteryIgnored) || (lastAlwaysOn == true && !alwaysOn)) {
                prefs[KEY_STABILITY_TIP_DISMISSED_AT] = 0L
                prefs[KEY_STABILITY_TIP_DISMISSED] = false
            }

            prefs[KEY_STABILITY_TIP_LAST_BATTERY_IGNORED] = batteryIgnored
            prefs[KEY_STABILITY_TIP_LAST_ALWAYS_ON] = alwaysOn
        }
    }

    suspend fun dismissFeatureTip(tipId: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_DISMISSED_FEATURE_TIPS] ?: emptySet()
            prefs[KEY_DISMISSED_FEATURE_TIPS] = current + tipId
        }
    }

    val preferredIPType: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PREFERRED_IP_TYPE] ?: PREFERRED_IP_AUTO
    }

    suspend fun getPreferredIPTypeSnapshot(): String = preferredIPType.first()

    suspend fun setPreferredIPType(type: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PREFERRED_IP_TYPE] = type
        }
    }

    val hasRatedApp: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_HAS_RATED_APP] ?: false
    }

    val ratePromptLastDismissed: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_RATE_PROMPT_LAST_DISMISSED] ?: 0L
    }

    suspend fun setHasRatedApp(rated: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_HAS_RATED_APP] = rated }
    }

    suspend fun setRatePromptLastDismissed(timestamp: Long) {
        dataStore.edit { prefs -> prefs[KEY_RATE_PROMPT_LAST_DISMISSED] = timestamp }
    }

    suspend fun reset() {
        dataStore.edit { reset(it) }
    }
}
