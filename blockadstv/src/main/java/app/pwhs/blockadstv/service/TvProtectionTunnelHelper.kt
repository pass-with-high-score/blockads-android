package app.pwhs.blockadstv.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.pwhs.blockadstv.data.datastore.ProtectionPreferences
import app.pwhs.blockadstv.data.datastore.TvPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class EffectiveDnsConfig(
    val protocol: String,
    val primary: String,
    val fallback: String,
    val dohUrl: String,
    val odohRelayUrl: String,
)

/**
 * Coordinates SafeSearch, YouTube Restricted Mode, and Trusted Network System-DNS
 * bypass for [TvVpnService] and [RootProxyService] without bloating service files.
 */
class TvProtectionTunnelHelper(
    private val context: Context,
    private val protectionPrefs: ProtectionPreferences,
) {
    suspend fun applyInitialProtection(goTunnelAdapter: GoTunnelAdapter) {
        val safeSearch = protectionPrefs.getSafeSearchEnabledSnapshot()
        val youtubeRestricted = protectionPrefs.getYoutubeRestrictedModeSnapshot()
        goTunnelAdapter.configureSafeSearch(safeSearch, youtubeRestricted)
    }

    suspend fun isTrustedNetworkWithSystemDns(): Boolean {
        if (!protectionPrefs.getUseSystemDnsOnTrustedEnabledSnapshot()) return false
        val currentNet = TrustedNetworkManager.currentNetworkName(context) ?: return false
        val trusted = protectionPrefs.getTrustedNetworksSnapshot()
        return currentNet in trusted
    }

    suspend fun resolveEffectiveDnsConfig(tvPrefs: TvPreferences): EffectiveDnsConfig {
        val protocol = tvPrefs.dnsProtocol.first().name
        val upstream = tvPrefs.upstreamDns.first()
        val fallback = tvPrefs.fallbackDns.first()
        val dohUrl = tvPrefs.dohUrl.first()
        val odohRelayUrl = tvPrefs.odohRelayUrl.first()

        if (isTrustedNetworkWithSystemDns()) {
            val systemDnsList = getSystemDnsServers()
            val (sysPrimary, sysFallback) = resolveSystemDnsPair(systemDnsList, fallback)
            Timber.d("Trusted network System DNS active: primary=$sysPrimary fallback=$sysFallback")
            return EffectiveDnsConfig(
                protocol = "PLAIN",
                primary = sysPrimary,
                fallback = sysFallback,
                dohUrl = dohUrl,
                odohRelayUrl = odohRelayUrl,
            )
        }

        return EffectiveDnsConfig(
            protocol = protocol,
            primary = upstream,
            fallback = fallback,
            dohUrl = dohUrl,
            odohRelayUrl = odohRelayUrl,
        )
    }

    suspend fun reloadEffectiveDns(tvPrefs: TvPreferences, goTunnelAdapter: GoTunnelAdapter) {
        val cfg = resolveEffectiveDnsConfig(tvPrefs)
        goTunnelAdapter.configureDns(
            protocol = cfg.protocol,
            primary = cfg.primary,
            fallback = cfg.fallback,
            dohUrl = cfg.dohUrl,
            odohRelayUrl = cfg.odohRelayUrl,
        )
    }

    fun observeProtectionUpdates(
        scope: CoroutineScope,
        tvPrefs: TvPreferences,
        goTunnelAdapter: GoTunnelAdapter,
        isRunning: () -> Boolean,
    ) {
        scope.launch {
            combine(
                protectionPrefs.safeSearchEnabled,
                protectionPrefs.youtubeRestrictedMode,
            ) { safeSearch, youtubeRestricted -> safeSearch to youtubeRestricted }
                .distinctUntilChanged()
                .drop(1)
                .collect { (safeSearch, youtubeRestricted) ->
                    goTunnelAdapter.configureSafeSearch(safeSearch, youtubeRestricted)
                }
        }

        scope.launch {
            combine(
                protectionPrefs.useSystemDnsOnTrustedEnabled,
                protectionPrefs.trustedNetworks,
            ) { enabled, networks -> enabled to networks }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (isRunning()) {
                        reloadEffectiveDns(tvPrefs, goTunnelAdapter)
                    }
                }
        }
    }

    private fun getSystemDnsServers(): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        try {
            @Suppress("DEPRECATION")
            val physicalNetworks = cm.allNetworks.filter { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            for (network in physicalNetworks) {
                val lp = cm.getLinkProperties(network) ?: continue
                val servers = lp.dnsServers.mapNotNull { it.hostAddress }
                    .filter { it.isNotEmpty() && !isInternalVpnDns(it) }
                if (servers.isNotEmpty()) return servers
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to query physical networks for DNS")
        }

        val activeLp = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        return activeLp?.dnsServers?.mapNotNull { it.hostAddress }
            ?.filter { it.isNotEmpty() && !isInternalVpnDns(it) }
            ?: emptyList()
    }

    private fun resolveSystemDnsPair(
        systemDnsList: List<String>,
        configuredFallback: String,
    ): Pair<String, String> {
        val filtered = systemDnsList.filter { it.isNotEmpty() && !isInternalVpnDns(it) }.distinct()
        if (filtered.isEmpty()) {
            return "8.8.8.8" to configuredFallback.ifEmpty { "1.1.1.1" }
        }
        val ipv4 = filtered.filter { !it.contains(":") }
        val ipv6 = filtered.filter { it.contains(":") }
        val primary = ipv4.firstOrNull() ?: filtered.first()
        val fallback = if (primary in ipv4) {
            ipv4.getOrNull(1) ?: ipv6.firstOrNull() ?: configuredFallback.ifEmpty { "1.1.1.1" }
        } else {
            ipv4.firstOrNull() ?: filtered.getOrNull(1) ?: configuredFallback.ifEmpty { "1.1.1.1" }
        }
        return primary to fallback
    }

    private fun isInternalVpnDns(ip: String): Boolean =
        ip.startsWith("10.10.10.") || ip.startsWith("fd00:10:10:")
}
