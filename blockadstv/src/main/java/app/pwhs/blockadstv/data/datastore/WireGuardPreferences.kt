package app.pwhs.blockadstv.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.pwhs.blockadstv.data.entities.WireGuardConfig
import app.pwhs.blockadstv.data.entities.WireGuardProfile
import app.pwhs.blockadstv.data.entities.WireGuardProfileList
import app.pwhs.blockadstv.utils.configIssue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.wgDataStore: DataStore<Preferences> by preferencesDataStore(name = "blockadstv_wireguard_prefs")

class WireGuardPreferences(private val context: Context) {

    companion object {
        const val ROUTING_MODE_WIREGUARD = "wireguard"

        private val KEY_WG_PROFILES_JSON = stringPreferencesKey("wg_profiles_json")
        private val KEY_WG_ACTIVE_PROFILE_ID = stringPreferencesKey("wg_active_profile_id")
        private val KEY_GEOIP_AUTO_UPDATE = booleanPreferencesKey("geoip_auto_update")
        private val KEY_GEOIP_CUSTOM_URL = stringPreferencesKey("geoip_custom_url")
    }

    val wgProfiles: Flow<List<WireGuardProfile>> = context.wgDataStore.data.map { prefs ->
        readProfilesFromPrefs(prefs)
    }

    val wgActiveProfileId: Flow<String?> = context.wgDataStore.data.map { prefs ->
        prefs[KEY_WG_ACTIVE_PROFILE_ID]
    }

    val geoIpAutoUpdate: Flow<Boolean> = context.wgDataStore.data.map { prefs ->
        prefs[KEY_GEOIP_AUTO_UPDATE] ?: true
    }

    val geoIpCustomUrl: Flow<String> = context.wgDataStore.data.map { prefs ->
        prefs[KEY_GEOIP_CUSTOM_URL] ?: ""
    }

    private fun readProfilesFromPrefs(prefs: Preferences): List<WireGuardProfile> {
        val json = prefs[KEY_WG_PROFILES_JSON] ?: return emptyList()
        return WireGuardProfileList.fromJson(json).profiles
    }

    suspend fun getWgProfilesSnapshot(): List<WireGuardProfile> = wgProfiles.first()

    suspend fun getActiveWgProfileSnapshot(): WireGuardProfile? {
        val profiles = getWgProfilesSnapshot()
        if (profiles.isEmpty()) return null
        val activeId = wgActiveProfileId.first()
        return profiles.firstOrNull { it.id == activeId }
            ?: profiles.firstOrNull { it.configIssue == null }
    }

    suspend fun getWgConfigJsonSnapshot(): String? {
        val active = getActiveWgProfileSnapshot() ?: return null
        return active.config.toJson()
    }

    suspend fun addOrUpdateWgProfile(profile: WireGuardProfile, makeActive: Boolean = false) {
        context.wgDataStore.edit { prefs ->
            val current = readProfilesFromPrefs(prefs).toMutableList()
            val idx = current.indexOfFirst { it.isSameProfile(profile) }
            val profileToSave = if (idx >= 0) {
                profile.copy(id = current[idx].id)
            } else {
                profile
            }
            if (idx >= 0) current[idx] = profileToSave else current.add(profileToSave)

            val distinct = deduplicateWgProfiles(current)
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(distinct).toJson()
            if (makeActive) prefs[KEY_WG_ACTIVE_PROFILE_ID] = profileToSave.id
        }
    }

    suspend fun removeWgProfile(id: String) {
        context.wgDataStore.edit { prefs ->
            val current = readProfilesFromPrefs(prefs).filterNot { it.id == id }
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(current).toJson()
            if (prefs[KEY_WG_ACTIVE_PROFILE_ID] == id) {
                val nextActive = current.firstOrNull { it.configIssue == null }?.id
                if (nextActive != null) {
                    prefs[KEY_WG_ACTIVE_PROFILE_ID] = nextActive
                } else {
                    prefs.remove(KEY_WG_ACTIVE_PROFILE_ID)
                }
            }
        }
    }

    suspend fun setActiveWgProfile(id: String) {
        context.wgDataStore.edit { prefs ->
            prefs[KEY_WG_ACTIVE_PROFILE_ID] = id
        }
    }

    suspend fun renameWgProfile(id: String, name: String) {
        context.wgDataStore.edit { prefs ->
            val current = readProfilesFromPrefs(prefs).map {
                if (it.id == id) it.copy(name = name) else it
            }
            prefs[KEY_WG_PROFILES_JSON] = WireGuardProfileList(current).toJson()
        }
    }

    suspend fun clearAllWgProfiles() {
        context.wgDataStore.edit { prefs ->
            prefs.remove(KEY_WG_PROFILES_JSON)
            prefs.remove(KEY_WG_ACTIVE_PROFILE_ID)
        }
    }

    suspend fun setGeoIpAutoUpdate(enabled: Boolean) {
        context.wgDataStore.edit { prefs ->
            prefs[KEY_GEOIP_AUTO_UPDATE] = enabled
        }
    }

    suspend fun setGeoIpCustomUrl(url: String) {
        context.wgDataStore.edit { prefs ->
            prefs[KEY_GEOIP_CUSTOM_URL] = url
        }
    }

    private fun deduplicateWgProfiles(list: List<WireGuardProfile>): List<WireGuardProfile> {
        val seen = mutableListOf<WireGuardProfile>()
        for (item in list) {
            if (seen.none { it.isSameProfile(item) }) {
                seen.add(item)
            }
        }
        return seen
    }
}
