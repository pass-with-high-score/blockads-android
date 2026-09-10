package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pwhs.blockads.data.entities.DnsProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DnsPreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        val KEY_UPSTREAM_DNS = stringPreferencesKey("upstream_dns")
        val KEY_FALLBACK_DNS = stringPreferencesKey("fallback_dns")
        val KEY_DNS_PROTOCOL = stringPreferencesKey("dns_protocol")
        val KEY_DOH_URL = stringPreferencesKey("doh_url")
        val KEY_DNS_PROVIDER_ID = stringPreferencesKey("dns_provider_id")
        val KEY_DNS_RESPONSE_TYPE = stringPreferencesKey("dns_response_type")
        val KEY_SPLIT_DNS_ZONES = stringPreferencesKey("split_dns_zones")

        const val DNS_RESPONSE_NXDOMAIN = "nxdomain"
        const val DNS_RESPONSE_REFUSED = "refused"
        const val DNS_RESPONSE_CUSTOM_IP = "custom_ip"

        const val DEFAULT_UPSTREAM_DNS = "9.9.9.9"
        const val DEFAULT_FALLBACK_DNS = "94.140.14.14"
        const val DEFAULT_DNS_PROTOCOL = "PLAIN"
        const val DEFAULT_DOH_URL = "https://dns.quad9.net/dns-query"
    }

    val upstreamDns: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_UPSTREAM_DNS] ?: DEFAULT_UPSTREAM_DNS
    }

    val fallbackDns: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_FALLBACK_DNS] ?: DEFAULT_FALLBACK_DNS
    }

    val dnsProtocol: Flow<DnsProtocol> = dataStore.data.map { prefs ->
        val protocolString = prefs[KEY_DNS_PROTOCOL] ?: DEFAULT_DNS_PROTOCOL
        try {
            DnsProtocol.valueOf(protocolString)
        } catch (_: IllegalArgumentException) {
            DnsProtocol.PLAIN
        }
    }

    val dohUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DOH_URL] ?: DEFAULT_DOH_URL
    }

    val dnsProviderId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_DNS_PROVIDER_ID]
    }

    val dnsResponseType: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DNS_RESPONSE_TYPE] ?: DNS_RESPONSE_CUSTOM_IP
    }

    val splitDnsZones: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SPLIT_DNS_ZONES] ?: ""
    }

    suspend fun setUpstreamDns(dns: String) {
        dataStore.edit { prefs ->
            prefs[KEY_UPSTREAM_DNS] = dns
        }
    }

    suspend fun setFallbackDns(dns: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FALLBACK_DNS] = dns
        }
    }

    suspend fun setDnsProtocol(protocol: DnsProtocol) {
        dataStore.edit { prefs ->
            prefs[KEY_DNS_PROTOCOL] = protocol.name
        }
    }

    suspend fun setDohUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DOH_URL] = url
        }
    }

    suspend fun setDnsProviderId(providerId: String?) {
        dataStore.edit { prefs ->
            if (providerId == null) {
                prefs.remove(KEY_DNS_PROVIDER_ID)
            } else {
                prefs[KEY_DNS_PROVIDER_ID] = providerId
            }
        }
    }

    suspend fun setDnsResponseType(responseType: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DNS_RESPONSE_TYPE] = responseType
        }
    }

    suspend fun setSplitDnsZones(zones: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SPLIT_DNS_ZONES] = zones
        }
    }
}
