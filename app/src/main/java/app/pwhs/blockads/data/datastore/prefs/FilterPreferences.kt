package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FilterPreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        val KEY_FILTER_URL = stringPreferencesKey("filter_url")
        val KEY_AUTO_UPDATE_ENABLED = booleanPreferencesKey("auto_update_enabled")
        val KEY_AUTO_UPDATE_FREQUENCY = stringPreferencesKey("auto_update_frequency")
        val KEY_AUTO_UPDATE_WIFI_ONLY = booleanPreferencesKey("auto_update_wifi_only")
        val KEY_AUTO_UPDATE_NOTIFICATION = stringPreferencesKey("auto_update_notification")
        val KEY_PROTECTION_LEVEL = stringPreferencesKey("protection_level")
        val KEY_SAFE_SEARCH_ENABLED = booleanPreferencesKey("safe_search_enabled")
        val KEY_YOUTUBE_RESTRICTED_MODE = booleanPreferencesKey("youtube_restricted_mode")

        const val DEFAULT_FILTER_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

        const val UPDATE_FREQUENCY_6H = "6h"
        const val UPDATE_FREQUENCY_12H = "12h"
        const val UPDATE_FREQUENCY_24H = "24h"
        const val UPDATE_FREQUENCY_48H = "48h"
        const val UPDATE_FREQUENCY_MANUAL = "manual"

        const val NOTIFICATION_SILENT = "silent"
        const val NOTIFICATION_NORMAL = "normal"
        const val NOTIFICATION_NONE = "none"

        const val PROTECTION_BASIC = "BASIC"
        const val PROTECTION_STANDARD = "STANDARD"
        const val PROTECTION_STRICT = "STRICT"
    }

    val filterUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_FILTER_URL] ?: DEFAULT_FILTER_URL
    }

    val autoUpdateEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_ENABLED] ?: true
    }

    val autoUpdateFrequency: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_FREQUENCY] ?: UPDATE_FREQUENCY_24H
    }

    val autoUpdateWifiOnly: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_WIFI_ONLY] ?: true
    }

    val autoUpdateNotification: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE_NOTIFICATION] ?: NOTIFICATION_SILENT
    }

    val protectionLevel: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_PROTECTION_LEVEL] ?: PROTECTION_STANDARD
    }

    val safeSearchEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SAFE_SEARCH_ENABLED] ?: false
    }

    val youtubeRestrictedMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_YOUTUBE_RESTRICTED_MODE] ?: false
    }

    suspend fun setFilterUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FILTER_URL] = url
        }
    }

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_ENABLED] = enabled
        }
    }

    suspend fun setAutoUpdateFrequency(frequency: String) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_FREQUENCY] = frequency
        }
    }

    suspend fun setAutoUpdateWifiOnly(wifiOnly: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun setAutoUpdateNotification(notificationType: String) {
        dataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_NOTIFICATION] = notificationType
        }
    }

    suspend fun setProtectionLevel(level: String) {
        dataStore.edit { prefs ->
            prefs[KEY_PROTECTION_LEVEL] = level
        }
    }

    suspend fun setSafeSearchEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SAFE_SEARCH_ENABLED] = enabled
        }
    }

    suspend fun setYoutubeRestrictedMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_YOUTUBE_RESTRICTED_MODE] = enabled
        }
    }
}
