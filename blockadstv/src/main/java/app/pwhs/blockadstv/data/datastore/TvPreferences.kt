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
import app.pwhs.blockadstv.data.entities.ProxyProfile
import app.pwhs.blockadstv.data.entities.ProxyProfileList
import app.pwhs.blockadstv.data.entities.ProxySubscription
import app.pwhs.blockadstv.data.entities.ProxySubscriptionList
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
        private val KEY_ODOH_RELAY_URL = stringPreferencesKey("odoh_relay_url")
        private val KEY_IS_CUSTOM_DNS = booleanPreferencesKey("is_custom_dns")
        private val KEY_CUSTOM_DNS_ENDPOINT = stringPreferencesKey("custom_dns_endpoint")
        private val KEY_DNS_RESPONSE_TYPE = stringPreferencesKey("dns_response_type")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_WHITELISTED_APPS = stringSetPreferencesKey("whitelisted_apps")
        private val KEY_FILTERS_DOWNLOADED = booleanPreferencesKey("filters_downloaded")
        private val KEY_ROUTING_MODE = stringPreferencesKey("routing_mode")
        private val KEY_PROXY_PROFILES_JSON = stringPreferencesKey("proxy_profiles_json")
        private val KEY_ACTIVE_PROXY_PROFILE_ID = stringPreferencesKey("active_proxy_profile_id")
        private val KEY_SUBSCRIPTIONS_JSON = stringPreferencesKey("proxy_subscriptions_json")

        const val ROUTING_MODE_DIRECT = "direct"
        const val ROUTING_MODE_PROXY_VPN = "proxy_vpn"
        const val ROUTING_MODE_ROOT = "root"

        const val DEFAULT_UPSTREAM_DNS = "9.9.9.9"
        const val DEFAULT_FALLBACK_DNS = "94.140.14.14"
        const val DEFAULT_DOH_URL = "https://dns.quad9.net/dns-query"
        const val DNS_RESPONSE_CUSTOM_IP = "custom_ip"
        const val DNS_RESPONSE_NXDOMAIN = "nxdomain"
        const val DNS_RESPONSE_REFUSED = "refused"

        const val THEME_DARK = "dark"
        const val THEME_OLED = "oled"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_EN = "en"
        const val LANGUAGE_VI = "vi"
        const val LANGUAGE_AR = "ar"
        const val LANGUAGE_CS = "cs"
        const val LANGUAGE_DE = "de"
        const val LANGUAGE_ES = "es"
        const val LANGUAGE_FR = "fr"
        const val LANGUAGE_IN = "in"
        const val LANGUAGE_IT = "it"
        const val LANGUAGE_IW = "iw"
        const val LANGUAGE_JA = "ja"
        const val LANGUAGE_KK = "kk"
        const val LANGUAGE_KO = "ko"
        const val LANGUAGE_PL = "pl"
        const val LANGUAGE_PT_BR = "pt-BR"
        const val LANGUAGE_RU = "ru"
        const val LANGUAGE_TH = "th"
        const val LANGUAGE_TR = "tr"
        const val LANGUAGE_UK = "uk"
        const val LANGUAGE_ZH = "zh"
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

    val odohRelayUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ODOH_RELAY_URL] ?: ""
    }

    val isCustomDns: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_IS_CUSTOM_DNS] ?: false
    }

    val customDnsEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CUSTOM_DNS_ENDPOINT] ?: ""
    }

    val dnsResponseType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DNS_RESPONSE_TYPE] ?: DNS_RESPONSE_CUSTOM_IP
    }

    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_START_ON_BOOT] ?: true
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: THEME_DARK
    }

    val appLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_APP_LANGUAGE] ?: LANGUAGE_SYSTEM
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

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
    }

    suspend fun setAppLanguage(language: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_LANGUAGE] = language
        }
    }

    val filtersDownloaded: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FILTERS_DOWNLOADED] ?: false
    }

    suspend fun setFiltersDownloaded(downloaded: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILTERS_DOWNLOADED] = downloaded
        }
    }

    suspend fun getWhitelistedAppsSnapshot(): Set<String> {
        return whitelistedApps.first()
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

    suspend fun setUpstreamDns(dns: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_UPSTREAM_DNS] = dns
        }
    }

    suspend fun setFallbackDns(dns: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FALLBACK_DNS] = dns
        }
    }

    suspend fun setDnsProtocol(protocol: DnsProtocol) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DNS_PROTOCOL] = protocol.name
        }
    }

    suspend fun setDohUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DOH_URL] = url
        }
    }

    suspend fun setOdohRelayUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ODOH_RELAY_URL] = url
        }
    }

    suspend fun setIsCustomDns(isCustom: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_CUSTOM_DNS] = isCustom
        }
    }

    suspend fun setCustomDnsEndpoint(endpoint: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_DNS_ENDPOINT] = endpoint
        }
    }

    suspend fun setDnsResponseType(type: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DNS_RESPONSE_TYPE] = type
        }
    }

    val routingMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ROUTING_MODE] ?: ROUTING_MODE_DIRECT
    }

    val proxyProfiles: Flow<List<ProxyProfile>> = context.dataStore.data.map { prefs ->
        prefs[KEY_PROXY_PROFILES_JSON]?.let { json ->
            ProxyProfileList.fromJson(json).profiles
        } ?: emptyList()
    }

    val activeProxyProfileId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_PROXY_PROFILE_ID]
    }

    val activeProxyProfile: Flow<ProxyProfile?> = context.dataStore.data.map { prefs ->
        val list = prefs[KEY_PROXY_PROFILES_JSON]?.let { ProxyProfileList.fromJson(it).profiles } ?: emptyList()
        val activeId = prefs[KEY_ACTIVE_PROXY_PROFILE_ID]
        list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
    }

    val proxySubscriptions: Flow<List<ProxySubscription>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SUBSCRIPTIONS_JSON]?.let { json ->
            ProxySubscriptionList.fromJson(json).subscriptions
        } ?: emptyList()
    }

    suspend fun setRoutingMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ROUTING_MODE] = mode
        }
    }

    suspend fun getRoutingModeSnapshot(): String = routingMode.first()

    suspend fun getProxyProfilesSnapshot(): List<ProxyProfile> = proxyProfiles.first()

    suspend fun getActiveProxyProfileSnapshot(): ProxyProfile? = activeProxyProfile.first()

    suspend fun getProxySubscriptionsSnapshot(): List<ProxySubscription> = proxySubscriptions.first()

    suspend fun setActiveProxyProfileId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id != null) {
                prefs[KEY_ACTIVE_PROXY_PROFILE_ID] = id
            } else {
                prefs.remove(KEY_ACTIVE_PROXY_PROFILE_ID)
            }
        }
    }

    suspend fun addProxyProfile(profile: ProxyProfile) {
        context.dataStore.edit { prefs ->
            val list = prefs[KEY_PROXY_PROFILES_JSON]?.let { ProxyProfileList.fromJson(it).profiles } ?: emptyList()
            val updated = list + profile
            prefs[KEY_PROXY_PROFILES_JSON] = ProxyProfileList(updated).toJson()
            if (prefs[KEY_ACTIVE_PROXY_PROFILE_ID] == null) {
                prefs[KEY_ACTIVE_PROXY_PROFILE_ID] = profile.id
            }
        }
    }

    suspend fun updateProxyProfile(profile: ProxyProfile) {
        context.dataStore.edit { prefs ->
            val list = prefs[KEY_PROXY_PROFILES_JSON]?.let { ProxyProfileList.fromJson(it).profiles } ?: emptyList()
            val updated = list.map { if (it.id == profile.id) profile else it }
            prefs[KEY_PROXY_PROFILES_JSON] = ProxyProfileList(updated).toJson()
        }
    }

    suspend fun deleteProxyProfile(id: String) {
        context.dataStore.edit { prefs ->
            val list = prefs[KEY_PROXY_PROFILES_JSON]?.let { ProxyProfileList.fromJson(it).profiles } ?: emptyList()
            val updated = list.filter { it.id != id }
            prefs[KEY_PROXY_PROFILES_JSON] = ProxyProfileList(updated).toJson()
            if (prefs[KEY_ACTIVE_PROXY_PROFILE_ID] == id) {
                val nextActive = updated.firstOrNull()?.id
                if (nextActive != null) {
                    prefs[KEY_ACTIVE_PROXY_PROFILE_ID] = nextActive
                } else {
                    prefs.remove(KEY_ACTIVE_PROXY_PROFILE_ID)
                }
            }
        }
    }

    suspend fun syncSubscriptionProfiles(subscription: ProxySubscription, newProfiles: List<ProxyProfile>) {
        context.dataStore.edit { prefs ->
            val subs = prefs[KEY_SUBSCRIPTIONS_JSON]?.let { ProxySubscriptionList.fromJson(it).subscriptions } ?: emptyList()
            val subUpdated = (subs.filter { it.id != subscription.id } + subscription)
            prefs[KEY_SUBSCRIPTIONS_JSON] = ProxySubscriptionList(subUpdated).toJson()

            val existing = prefs[KEY_PROXY_PROFILES_JSON]?.let { ProxyProfileList.fromJson(it).profiles } ?: emptyList()
            val otherProfiles = existing.filter { it.subscriptionId != subscription.id }
            val attachedProfiles = newProfiles.map {
                it.copy(subscriptionId = subscription.id, subscriptionUrl = subscription.url)
            }
            val merged = otherProfiles + attachedProfiles
            prefs[KEY_PROXY_PROFILES_JSON] = ProxyProfileList(merged).toJson()

            if (prefs[KEY_ACTIVE_PROXY_PROFILE_ID] == null && attachedProfiles.isNotEmpty()) {
                prefs[KEY_ACTIVE_PROXY_PROFILE_ID] = attachedProfiles.first().id
            }
        }
    }

    suspend fun removeSubscription(id: String, deleteNodes: Boolean = true) {
        context.dataStore.edit { prefs ->
            val subs = prefs[KEY_SUBSCRIPTIONS_JSON]?.let { ProxySubscriptionList.fromJson(it).subscriptions } ?: emptyList()
            prefs[KEY_SUBSCRIPTIONS_JSON] = ProxySubscriptionList(subs.filter { it.id != id }).toJson()

            if (deleteNodes) {
                val existing = prefs[KEY_PROXY_PROFILES_JSON]?.let { ProxyProfileList.fromJson(it).profiles } ?: emptyList()
                val kept = existing.filter { it.subscriptionId != id }
                prefs[KEY_PROXY_PROFILES_JSON] = ProxyProfileList(kept).toJson()
                if (existing.any { it.id == prefs[KEY_ACTIVE_PROXY_PROFILE_ID] && it.subscriptionId == id }) {
                    prefs.remove(KEY_ACTIVE_PROXY_PROFILE_ID)
                }
            }
        }
    }
}
