package app.pwhs.blockadstv.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.pwhs.blockadstv.data.entities.DnsProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "blockadstv_prefs")

class TvPreferences(private val context: Context) {

    companion object {
        private val KEY_VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
        private val KEY_UPSTREAM_DNS = stringPreferencesKey("upstream_dns")
        private val KEY_FALLBACK_DNS = stringPreferencesKey("fallback_dns")
        private val KEY_DNS_PROTOCOL = stringPreferencesKey("dns_protocol")
        private val KEY_DOH_URL = stringPreferencesKey("doh_url")
        private val KEY_DNS_RESPONSE_TYPE = stringPreferencesKey("dns_response_type")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_WHITELISTED_APPS = stringSetPreferencesKey("whitelisted_apps")

        const val DEFAULT_UPSTREAM_DNS = "9.9.9.9"
        const val DEFAULT_FALLBACK_DNS = "94.140.14.14"
        const val DEFAULT_DOH_URL = "https://dns.quad9.net/dns-query"
        const val DNS_RESPONSE_CUSTOM_IP = "custom_ip"
    }

    val vpnEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VPN_ENABLED] ?: false
    }

    val upstreamDns: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_UPSTREAM_DNS] ?: DEFAULT_UPSTREAM_DNS
    }

    val fallbackDns: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FALLBACK_DNS] ?: DEFAULT_FALLBACK_DNS
    }

    val dnsProtocol: Flow<DnsProtocol> = context.dataStore.data.map { prefs ->
        val protocolString = prefs[KEY_DNS_PROTOCOL] ?: "PLAIN"
        try {
            DnsProtocol.valueOf(protocolString)
        } catch (e: IllegalArgumentException) {
            DnsProtocol.PLAIN
        }
    }

    val dohUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOH_URL] ?: DEFAULT_DOH_URL
    }

    val dnsResponseType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DNS_RESPONSE_TYPE] ?: DNS_RESPONSE_CUSTOM_IP
    }

    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_START_ON_BOOT] ?: true
    }

    val whitelistedApps: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_WHITELISTED_APPS] ?: emptySet()
    }

    suspend fun setVpnEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VPN_ENABLED] = enabled
        }
    }

    suspend fun setStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_START_ON_BOOT] = enabled
        }
    }

    suspend fun getWhitelistedAppsSnapshot(): Set<String> {
        return whitelistedApps.first()
    }
}
