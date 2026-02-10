package app.pwhs.blockads.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_WHITELISTED_APPS = stringSetPreferencesKey("whitelisted_apps")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"

        const val DEFAULT_FILTER_URL = "https://abpvn.com/android/abpvn.txt"
        const val DEFAULT_UPSTREAM_DNS = "8.8.8.8"
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

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val whitelistedApps: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_WHITELISTED_APPS] ?: emptySet()
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: THEME_SYSTEM
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

    suspend fun getWhitelistedAppsSnapshot(): Set<String> {
        return whitelistedApps.first()
    }
}
