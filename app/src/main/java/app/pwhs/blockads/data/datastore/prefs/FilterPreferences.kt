package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val KEY_STARRED_FILTER_URLS = stringSetPreferencesKey("starred_filter_urls")
        val KEY_GEOIP_CUSTOM_URL = stringPreferencesKey("geoip_custom_url")
        val KEY_GEOIP_AUTO_UPDATE = booleanPreferencesKey("geoip_auto_update")
        val KEY_FILTER_BUILD_MODE = stringPreferencesKey("filter_build_mode")
        val KEY_CUSTOM_FILTER_TAGS = stringSetPreferencesKey("custom_filter_tags")
        val KEY_STORE_USE_DEFAULT_FILTERS = booleanPreferencesKey("store_use_default_filters")

        val DEFAULT_CUSTOM_TAGS = emptySet<String>()

        const val DEFAULT_FILTER_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

        const val BUILD_MODE_ONLINE = "online"
        const val BUILD_MODE_LOCAL = "local"

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

        fun reset(prefs: MutablePreferences) {
            prefs.remove(KEY_FILTER_URL)
            prefs.remove(KEY_AUTO_UPDATE_ENABLED)
            prefs.remove(KEY_AUTO_UPDATE_FREQUENCY)
            prefs.remove(KEY_AUTO_UPDATE_WIFI_ONLY)
            prefs.remove(KEY_AUTO_UPDATE_NOTIFICATION)
            prefs.remove(KEY_PROTECTION_LEVEL)
            prefs.remove(KEY_SAFE_SEARCH_ENABLED)
            prefs.remove(KEY_YOUTUBE_RESTRICTED_MODE)
            prefs.remove(KEY_STARRED_FILTER_URLS)
            prefs.remove(KEY_GEOIP_CUSTOM_URL)
            prefs.remove(KEY_GEOIP_AUTO_UPDATE)
            prefs.remove(KEY_FILTER_BUILD_MODE)
            prefs.remove(KEY_CUSTOM_FILTER_TAGS)
            prefs.remove(KEY_STORE_USE_DEFAULT_FILTERS)
        }
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

    val filterBuildMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_FILTER_BUILD_MODE] ?: BUILD_MODE_ONLINE
    }

    suspend fun setFilterBuildMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FILTER_BUILD_MODE] = mode
        }
    }

    suspend fun getFilterBuildModeSnapshot(): String =
        dataStore.data.map { it[KEY_FILTER_BUILD_MODE] ?: BUILD_MODE_ONLINE }.first()

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

    val geoIpCustomUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_GEOIP_CUSTOM_URL] ?: ""
    }

    val geoIpAutoUpdate: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_GEOIP_AUTO_UPDATE] ?: true
    }

    suspend fun setGeoIpCustomUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[KEY_GEOIP_CUSTOM_URL] = url
        }
    }

    suspend fun setGeoIpAutoUpdate(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_GEOIP_AUTO_UPDATE] = enabled
        }
    }

    val starredFilterUrls: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_STARRED_FILTER_URLS] ?: emptySet()
    }

    suspend fun toggleStarredFilter(url: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_STARRED_FILTER_URLS] ?: emptySet()
            prefs[KEY_STARRED_FILTER_URLS] = if (current.contains(url)) {
                current - url
            } else {
                current + url
            }
        }
    }

    val customFilterTags: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_FILTER_TAGS] ?: DEFAULT_CUSTOM_TAGS
    }

    val storeUseDefaultFilters: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_STORE_USE_DEFAULT_FILTERS] ?: false
    }

    suspend fun setStoreUseDefaultFilters(enabled: Boolean) {
        dataStore.edit { it[KEY_STORE_USE_DEFAULT_FILTERS] = enabled }
    }

    suspend fun addCustomFilterTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isBlank()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_CUSTOM_FILTER_TAGS] ?: DEFAULT_CUSTOM_TAGS
            prefs[KEY_CUSTOM_FILTER_TAGS] = current + trimmed
        }
    }

    suspend fun removeCustomFilterTag(tag: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_CUSTOM_FILTER_TAGS] ?: DEFAULT_CUSTOM_TAGS
            prefs[KEY_CUSTOM_FILTER_TAGS] = current - tag
        }
    }

    suspend fun reset() {
        dataStore.edit { reset(it) }
    }
}
