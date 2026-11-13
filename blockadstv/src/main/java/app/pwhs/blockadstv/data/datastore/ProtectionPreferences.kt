package app.pwhs.blockadstv.data.datastore

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

private val Context.protectionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "blockadstv_protection_prefs",
)

/**
 * Stores SafeSearch, YouTube Restricted Mode, and Trusted Network preferences
 * separately from [TvPreferences] to keep preference files focused and concise.
 */
class ProtectionPreferences(private val context: Context) {

    companion object {
        private val KEY_SAFE_SEARCH_ENABLED = booleanPreferencesKey("safe_search_enabled")
        private val KEY_YOUTUBE_RESTRICTED_MODE = booleanPreferencesKey("youtube_restricted_mode")
        private val KEY_TRUSTED_NETWORKS = stringSetPreferencesKey("trusted_networks")
        private val KEY_PAUSE_ON_TRUSTED = booleanPreferencesKey("pause_on_trusted")
        private val KEY_USE_SYSTEM_DNS_ON_TRUSTED = booleanPreferencesKey("use_system_dns_on_trusted")
        private val KEY_PAUSED_BY_TRUSTED = booleanPreferencesKey("paused_by_trusted")
        private val KEY_PAUSED_TRUSTED_NETWORK = stringPreferencesKey("paused_trusted_network")
    }

    val safeSearchEnabled: Flow<Boolean> = context.protectionDataStore.data.map { prefs ->
        prefs[KEY_SAFE_SEARCH_ENABLED] ?: false
    }

    val youtubeRestrictedMode: Flow<Boolean> = context.protectionDataStore.data.map { prefs ->
        prefs[KEY_YOUTUBE_RESTRICTED_MODE] ?: false
    }

    val trustedNetworks: Flow<Set<String>> = context.protectionDataStore.data.map { prefs ->
        prefs[KEY_TRUSTED_NETWORKS] ?: emptySet()
    }

    val pauseOnTrustedEnabled: Flow<Boolean> = context.protectionDataStore.data.map { prefs ->
        prefs[KEY_PAUSE_ON_TRUSTED] ?: false
    }

    val useSystemDnsOnTrustedEnabled: Flow<Boolean> = context.protectionDataStore.data.map { prefs ->
        prefs[KEY_USE_SYSTEM_DNS_ON_TRUSTED] ?: false
    }

    val pausedByTrusted: Flow<Boolean> = context.protectionDataStore.data.map { prefs ->
        prefs[KEY_PAUSED_BY_TRUSTED] ?: false
    }

    val pausedTrustedNetwork: Flow<String> = context.protectionDataStore.data.map { prefs ->
        prefs[KEY_PAUSED_TRUSTED_NETWORK] ?: ""
    }

    suspend fun setSafeSearchEnabled(enabled: Boolean) {
        context.protectionDataStore.edit { it[KEY_SAFE_SEARCH_ENABLED] = enabled }
    }

    suspend fun getSafeSearchEnabledSnapshot(): Boolean = safeSearchEnabled.first()

    suspend fun setYoutubeRestrictedMode(enabled: Boolean) {
        context.protectionDataStore.edit { it[KEY_YOUTUBE_RESTRICTED_MODE] = enabled }
    }

    suspend fun getYoutubeRestrictedModeSnapshot(): Boolean = youtubeRestrictedMode.first()

    suspend fun setTrustedNetworks(networks: Set<String>) {
        context.protectionDataStore.edit { it[KEY_TRUSTED_NETWORKS] = networks }
    }

    suspend fun toggleTrustedNetwork(network: String) {
        val normalized = network.trim()
        if (normalized.isEmpty()) return
        context.protectionDataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_NETWORKS] ?: emptySet()
            prefs[KEY_TRUSTED_NETWORKS] =
                if (normalized in current) current - normalized else current + normalized
        }
    }

    suspend fun addTrustedNetwork(network: String) {
        val normalized = network.trim()
        if (normalized.isEmpty()) return
        context.protectionDataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_NETWORKS] ?: emptySet()
            prefs[KEY_TRUSTED_NETWORKS] = current + normalized
        }
    }

    suspend fun removeTrustedNetwork(network: String) {
        context.protectionDataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_NETWORKS] ?: emptySet()
            prefs[KEY_TRUSTED_NETWORKS] = current - network
        }
    }

    suspend fun getTrustedNetworksSnapshot(): Set<String> = trustedNetworks.first()

    suspend fun setPauseOnTrustedEnabled(enabled: Boolean) {
        context.protectionDataStore.edit { it[KEY_PAUSE_ON_TRUSTED] = enabled }
    }

    suspend fun getPauseOnTrustedEnabledSnapshot(): Boolean = pauseOnTrustedEnabled.first()

    suspend fun setUseSystemDnsOnTrustedEnabled(enabled: Boolean) {
        context.protectionDataStore.edit { it[KEY_USE_SYSTEM_DNS_ON_TRUSTED] = enabled }
    }

    suspend fun getUseSystemDnsOnTrustedEnabledSnapshot(): Boolean =
        useSystemDnsOnTrustedEnabled.first()

    suspend fun setPausedByTrusted(value: Boolean, network: String = "") {
        context.protectionDataStore.edit {
            it[KEY_PAUSED_BY_TRUSTED] = value
            it[KEY_PAUSED_TRUSTED_NETWORK] = if (value) network else ""
        }
    }

    suspend fun getPausedByTrustedSnapshot(): Boolean = pausedByTrusted.first()
}
