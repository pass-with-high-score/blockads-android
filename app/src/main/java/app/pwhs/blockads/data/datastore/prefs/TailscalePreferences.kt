package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class TailscalePreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        const val DEFAULT_CONTROL_URL = "https://controlplane.tailscale.com"

        val KEY_TAILSCALE_ENABLED = booleanPreferencesKey("tailscale_enabled")
        val KEY_TAILSCALE_STATE_JSON = stringPreferencesKey("tailscale_state_json")
        val KEY_TAILSCALE_CONTROL_URL = stringPreferencesKey("tailscale_control_url")
        val KEY_TAILSCALE_AUTH_KEY = stringPreferencesKey("tailscale_auth_key")
        val KEY_TAILSCALE_EXIT_NODE_ID = longPreferencesKey("tailscale_exit_node_id")
        val KEY_TAILSCALE_AUTO_MAGIC_DNS = booleanPreferencesKey("tailscale_auto_magic_dns")

        fun reset(prefs: MutablePreferences) {
            prefs.remove(KEY_TAILSCALE_ENABLED)
            prefs.remove(KEY_TAILSCALE_STATE_JSON)
            prefs.remove(KEY_TAILSCALE_CONTROL_URL)
            prefs.remove(KEY_TAILSCALE_AUTH_KEY)
            prefs.remove(KEY_TAILSCALE_EXIT_NODE_ID)
            prefs.remove(KEY_TAILSCALE_AUTO_MAGIC_DNS)
        }
    }

    val enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TAILSCALE_ENABLED] ?: false
    }

    val stateJson: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TAILSCALE_STATE_JSON] ?: ""
    }

    val controlUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TAILSCALE_CONTROL_URL] ?: DEFAULT_CONTROL_URL
    }

    val authKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TAILSCALE_AUTH_KEY] ?: ""
    }

    val exitNodeId: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_TAILSCALE_EXIT_NODE_ID] ?: 0L
    }

    val autoMagicDns: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_TAILSCALE_AUTO_MAGIC_DNS] ?: true
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TAILSCALE_ENABLED] = enabled
        }
    }

    suspend fun setStateJson(json: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TAILSCALE_STATE_JSON] = json
        }
    }

    suspend fun setControlUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TAILSCALE_CONTROL_URL] = url
        }
    }

    suspend fun setAuthKey(key: String) {
        dataStore.edit { prefs ->
            prefs[KEY_TAILSCALE_AUTH_KEY] = key
        }
    }

    suspend fun setExitNodeId(id: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_TAILSCALE_EXIT_NODE_ID] = id
        }
    }

    suspend fun setAutoMagicDns(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_TAILSCALE_AUTO_MAGIC_DNS] = enabled
        }
    }

    suspend fun isEnabledSnapshot(): Boolean = enabled.first()
    suspend fun getStateJsonSnapshot(): String = stateJson.first()
    suspend fun getControlUrlSnapshot(): String = controlUrl.first()
    suspend fun getAuthKeySnapshot(): String = authKey.first()
    suspend fun getExitNodeIdSnapshot(): Long = exitNodeId.first()
}
