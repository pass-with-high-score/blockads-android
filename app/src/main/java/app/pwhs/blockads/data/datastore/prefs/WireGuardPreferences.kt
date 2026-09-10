package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.data.entities.WireGuardProfileList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class WireGuardPreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        val KEY_ROUTING_MODE = stringPreferencesKey("routing_mode")
        val KEY_WG_CONFIG_JSON = stringPreferencesKey("wg_config_json")
        val KEY_WG_PROFILES_JSON = stringPreferencesKey("wg_profiles_json")
        val KEY_WG_ACTIVE_PROFILE_ID = stringPreferencesKey("wg_active_profile_id")
        val KEY_EXCLUDE_LAN = booleanPreferencesKey("exclude_lan")

        const val ROUTING_MODE_DIRECT = "direct"
        const val ROUTING_MODE_WIREGUARD = "wireguard"
        const val ROUTING_MODE_ROOT = "root"

        private const val LEGACY_PROFILE_ID = "legacy-default"
    }

    val routingMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_ROUTING_MODE] ?: ROUTING_MODE_DIRECT
    }

    val wgProfiles: Flow<List<WireGuardProfile>> = dataStore.data.map { prefs ->
        readProfilesFromPrefs(prefs)
    }

    val wgActiveProfileId: Flow<String?> = dataStore.data.map { prefs ->
        readActiveIdFromPrefs(prefs)
    }

    val excludeLan: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_EXCLUDE_LAN] ?: false
    }

    private fun readProfilesFromPrefs(prefs: Preferences): List<WireGuardProfile> {
        prefs[KEY_WG_PROFILES_JSON]?.let {
            return WireGuardProfileList.fromJson(it).profiles
        }
        prefs[KEY_WG_CONFIG_JSON]?.let { legacy ->
            val cfg = try {
                WireGuardConfig.fromJson(legacy)
            } catch (_: Exception) {
                return emptyList()
            }
            return listOf(WireGuardProfile(LEGACY_PROFILE_ID, "Default", cfg))
        }
        return emptyList()
    }

    private fun readActiveIdFromPrefs(prefs: Preferences): String? {
        prefs[KEY_WG_ACTIVE_PROFILE_ID]?.let { return it }
        if (prefs[KEY_WG_CONFIG_JSON] != null) return LEGACY_PROFILE_ID
        return null
    }

    suspend fun setRoutingMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[KEY_ROUTING_MODE] = mode
        }
    }

    suspend fun getRoutingModeSnapshot(): String {
        return routingMode.first()
    }

    suspend fun getWgConfigJsonSnapshot(): String? {
        val active = getActiveWgProfileSnapshot() ?: return null
        return active.config.toJson()
    }

    suspend fun getWgProfilesSnapshot(): List<WireGuardProfile> = wgProfiles.first()

    suspend fun getActiveWgProfileSnapshot(): WireGuardProfile? {
        val profiles = getWgProfilesSnapshot()
        if (profiles.isEmpty()) return null
        val activeId = wgActiveProfileId.first()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    suspend fun addOrUpdateWgProfile(profile: WireGuardProfile, makeActive: Boolean = false) {
        dataStore.edit { prefs ->
            val current = readProfilesFromPrefs(prefs).toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current[idx] = profile else current.add(profile)
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(current).toJson()
            if (makeActive) prefs[KEY_WG_ACTIVE_PROFILE_ID] = profile.id
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun removeWgProfile(id: String) {
        dataStore.edit { prefs ->
            val current = readProfilesFromPrefs(prefs).filterNot { it.id == id }
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(current).toJson()
            if (prefs[KEY_WG_ACTIVE_PROFILE_ID] == id) {
                val newActive = current.firstOrNull()?.id
                if (newActive != null) {
                    prefs[KEY_WG_ACTIVE_PROFILE_ID] = newActive
                } else {
                    prefs.remove(KEY_WG_ACTIVE_PROFILE_ID)
                }
            }
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun setActiveWgProfile(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY_WG_ACTIVE_PROFILE_ID] = id
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun renameWgProfile(id: String, name: String) {
        dataStore.edit { prefs ->
            val current = readProfilesFromPrefs(prefs).map {
                if (it.id == id) it.copy(name = name) else it
            }
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(current).toJson()
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun clearAllWgProfiles() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_WG_PROFILES_JSON)
            prefs.remove(KEY_WG_ACTIVE_PROFILE_ID)
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun migrateLegacyWgConfigIfNeeded() {
        dataStore.edit { prefs ->
            if (prefs[KEY_WG_PROFILES_JSON] != null) {
                prefs.remove(KEY_WG_CONFIG_JSON)
                return@edit
            }
            val legacyJson = prefs[KEY_WG_CONFIG_JSON] ?: return@edit
            val cfg = try {
                WireGuardConfig.fromJson(legacyJson)
            } catch (_: Exception) {
                prefs.remove(KEY_WG_CONFIG_JSON)
                return@edit
            }
            val profile = WireGuardProfile(LEGACY_PROFILE_ID, "Default", cfg)
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(listOf(profile)).toJson()
            prefs[KEY_WG_ACTIVE_PROFILE_ID] = profile.id
            prefs.remove(KEY_WG_CONFIG_JSON)
        }
    }

    suspend fun setExcludeLan(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_EXCLUDE_LAN] = enabled
        }
    }
}
