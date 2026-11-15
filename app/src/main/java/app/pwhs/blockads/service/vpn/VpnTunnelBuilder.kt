package app.pwhs.blockads.service.vpn

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.utils.NetworkInfoHelper
import app.pwhs.blockads.utils.SubnetDecomposer
import app.pwhs.blockads.utils.WgConfigIssue
import app.pwhs.blockads.utils.wgRoutingFromPeers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress

sealed interface TunnelResult {
    data class Success(
        val vpnInterface: ParcelFileDescriptor,
        val resolvedWgConfigJson: String
    ) : TunnelResult

    data object PermissionRevoked : TunnelResult
    data class InvalidWireGuardConfig(val issue: WgConfigIssue) : TunnelResult
    data object Failure : TunnelResult
}

class VpnTunnelBuilder(
    private val vpnService: VpnService,
    private val appPrefs: AppPreferences,
    private val hasIpv6Provider: () -> Boolean = { NetworkInfoHelper.hasRoutableIpv6(vpnService) }
) {

    fun establish(whitelistedApps: Set<String>): TunnelResult {
        if (VpnService.prepare(vpnService) != null) {
            Timber.e("VPN is not prepared or permission was revoked.")
            return TunnelResult.PermissionRevoked
        }

        return try {
            val routingMode = runBlocking {
                appPrefs.getRoutingModeSnapshot()
            }
            var resolvedWgConfigJson = ""
            val isWg = when (routingMode) {
                AppPreferences.ROUTING_MODE_WIREGUARD -> true
                AppPreferences.ROUTING_MODE_PROXY_VPN -> runBlocking { appPrefs.getActiveProxyTypeSnapshot() } == AppPreferences.PROXY_TYPE_WIREGUARD
                else -> false
            }

            val isSs = when (routingMode) {
                AppPreferences.ROUTING_MODE_SHADOWSOCKS -> true
                AppPreferences.ROUTING_MODE_PROXY_VPN -> runBlocking {
                    try {
                        appPrefs.getActiveProxyTypeSnapshot()
                    } catch (_: Exception) {
                        ""
                    }
                } == AppPreferences.PROXY_TYPE_SHADOWSOCKS
                else -> false
            }

            val isProxyOutbound = when (routingMode) {
                AppPreferences.ROUTING_MODE_SHADOWSOCKS -> true
                AppPreferences.ROUTING_MODE_PROXY_VPN -> {
                    val type = runBlocking {
                        try {
                            appPrefs.getActiveProxyTypeSnapshot()
                        } catch (_: Exception) {
                            ""
                        }
                    }
                    type.isNotEmpty() && type != AppPreferences.PROXY_TYPE_WIREGUARD
                }
                else -> false
            }

            val wgConfig: WireGuardConfig? =
                if (isWg) {
                    val json = runBlocking { appPrefs.getWgConfigJsonSnapshot() }
                    json?.let {
                        try {
                            val parsed = WireGuardConfig.fromJson(it)
                            resolveWireGuardEndpoints(parsed)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to parse WireGuard config, falling back to direct")
                            null
                        }
                    }
                } else null

            val builder = if (wgConfig != null) {
                Timber.d("Establishing VPN in WireGuard mode")
                val appName = vpnService.getString(R.string.app_name)
                val b = vpnService.Builder()
                    .setSession("$appName WireGuard")
                    .setBlocking(false)
                    .setMtu(1280)

                for (addr in wgConfig.interfaceConfig.address) {
                    val parts = addr.split("/")
                    val ip = parts[0]
                    val prefix = parts.getOrNull(1)?.toIntOrNull()
                    if (ip.contains(":")) {
                        b.addAddress(ip, prefix ?: 128)
                    } else {
                        b.addAddress(ip, prefix ?: 32)
                    }
                }

                // Fail closed: connecting with routes missing would send traffic the user expects tunneled out directly.
                val routing = wgRoutingFromPeers(wgConfig.peers)
                routing.issue?.let {
                    Timber.e("WireGuard: refusing to connect, $it")
                    return TunnelResult.InvalidWireGuardConfig(it)
                }
                // wireguard-go must get the same normalized entries the TUN routes; one bad entry fails the whole device.
                resolvedWgConfigJson = wgConfig.copy(peers = routing.peers).toJson()
                val excludeLan = runBlocking { appPrefs.excludeLan.first() }
                for (route in routing.tunRoutes) {
                    // The decomposed routes stand in for 0.0.0.0/0 and also work below API 33.
                    if (excludeLan && !route.isIpv6 && route.prefixLength == 0) {
                        addIpv4Routes(b, excludeLan = true)
                    } else if (excludeLan && route.isIpv6 && route.prefixLength == 0) {
                        addIpv6Routes(b, excludeLan = true)
                    } else {
                        b.addRoute(route.address, route.prefixLength)
                    }
                }
                Timber.d("WireGuard routes=${routing.tunRoutes.size} fullTunnel=${routing.isFullTunnel}")

                // Full tunnel only: excludeRoute overrides a matching addRoute, so in split mode it would untunnel a listed LAN range.
                if (routing.isFullTunnel && excludeLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        val wgAddrs = wgConfig.interfaceConfig.address.map { it.split("/")[0] }
                        if (wgAddrs.none { it.startsWith("10.") }) {
                            b.excludeRoute(IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
                        }
                        if (wgAddrs.none { addr -> (16..31).any { addr.startsWith("172.$it.") } }) {
                            b.excludeRoute(IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
                        }
                        if (wgAddrs.none { it.startsWith("192.168.") }) {
                            b.excludeRoute(IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
                        }
                        b.excludeRoute(IpPrefix(InetAddress.getByName("169.254.0.0"), 16))
                        // Do NOT exclude fc00::/7 because fd00::1 (our synthetic DNS) is within fc00::/7
                        b.excludeRoute(IpPrefix(InetAddress.getByName("fe80::"), 10))
                        Timber.d("LAN excluded from WireGuard VPN routes")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to exclude LAN routes")
                    }
                }

                b.addAddress("100.64.100.2", 32)
                b.addDnsServer("100.64.100.1")
                b.addRoute("100.64.100.1", 32)
                b.addAddress("fd00::2", 128)
                b.addDnsServer("fd00::1")
                b.addRoute("fd00::1", 128)
                b
            } else {
                val excludeLan = runBlocking { appPrefs.excludeLan.first() }
                val preferredIp = runBlocking { appPrefs.preferredIPType.first() }
                val allowIpv6 = when (preferredIp) {
                    AppPreferences.PREFERRED_IP_IPV4 -> false
                    AppPreferences.PREFERRED_IP_IPV6 -> true
                    else -> hasIpv6Provider()
                }
                val allowIpv4 = preferredIp != AppPreferences.PREFERRED_IP_IPV6
                Timber.d("Establishing VPN in direct mode (fullTunnel=true, excludeLan=$excludeLan, preferredIp=$preferredIp, allowIpv6=$allowIpv6)")
                val appName = vpnService.getString(R.string.app_name)
                val b = vpnService.Builder()
                    .setSession(if (isSs) "$appName Outline" else appName)
                    .addAddress("100.64.100.2", 32)
                    .addRoute("100.64.100.1", 32)
                    .addDnsServer("100.64.100.1")
                    .setBlocking(false)
                    .setMtu(1350)
                if (allowIpv6) {
                    b.addAddress("fd00::2", 128)
                    b.addRoute("fd00::1", 128)
                    b.addDnsServer("fd00::1")
                }
                if (allowIpv4) {
                    addIpv4Routes(b, excludeLan)
                }
                if (allowIpv6) {
                    addIpv6Routes(b, excludeLan)
                }
                if (excludeLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        if (allowIpv4) {
                            b.excludeRoute(IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
                            b.excludeRoute(IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
                            b.excludeRoute(IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
                            b.excludeRoute(IpPrefix(InetAddress.getByName("169.254.0.0"), 16))
                        }
                        if (allowIpv6) {
                            // Do NOT exclude fc00::/7 because fd00::1 (our synthetic DNS) is within fc00::/7
                            b.excludeRoute(IpPrefix(InetAddress.getByName("fe80::"), 10))
                        }
                        Timber.d("LAN excluded from direct mode VPN routes")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to exclude LAN routes")
                    }
                }
                if (isProxyOutbound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val activeProxy = runBlocking {
                        try {
                            appPrefs.getActiveProxyProfileSnapshot()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val proxyHost = activeProxy?.server?.trim()
                    if (!proxyHost.isNullOrEmpty()) {
                        try {
                            val addrs = InetAddress.getAllByName(proxyHost)
                            for (addr in addrs) {
                                val prefix = if (addr is Inet4Address) 32 else 128
                                b.excludeRoute(IpPrefix(addr, prefix))
                                Timber.d("Proxy server $addr excluded from VPN routes")
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to exclude proxy server $proxyHost from VPN routes")
                        }
                    }
                }
                b
            }

            val allExcludedApps = (whitelistedApps + vpnService.packageName)
            for (appPackage in allExcludedApps) {
                try {
                    builder.addDisallowedApplication(appPackage)
                    Timber.d("Excluded from VPN: $appPackage")
                } catch (e: Exception) {
                    Timber.w(e, "Could not exclude $appPackage from VPN")
                }
            }

            // A non-bypassable tunnel refuses an app's explicit bind to an
            // underlying network, which is what breaks wireless Android Auto.
            val allowAppBypass = runBlocking { appPrefs.allowAppBypass.first() }
            if (allowAppBypass) {
                builder.allowBypass()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setUnderlyingNetworks(null)
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd != null) {
                try {
                    vpnService.setUnderlyingNetworks(null)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to set underlying network on VpnService")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val flags = android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_GETFL, 0)
                        android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_SETFL, flags or android.system.OsConstants.O_NONBLOCK)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to set TUN O_NONBLOCK via fcntl")
                    }
                }
                TunnelResult.Success(pfd, resolvedWgConfigJson)
            } else {
                Timber.e("Failed to establish VPN interface")
                TunnelResult.Failure
            }
        } catch (e: Exception) {
            Timber.e(e, "Error establishing VPN")
            TunnelResult.Failure
        }
    }

    private fun resolveWireGuardEndpoints(config: WireGuardConfig): WireGuardConfig {
        val resolvedPeers = config.peers.map { peer ->
            val endpoint = peer.endpoint ?: return@map peer
            val parts = endpoint.split(":")
            if (parts.size != 2) return@map peer

            val host = parts[0]
            val port = parts[1]

            try {
                InetAddress.getByName(host).also {
                    if (it.hostAddress == host) return@map peer
                }
            } catch (_: Exception) { }

            try {
                val addresses = InetAddress.getAllByName(host)
                val resolved = addresses.firstOrNull { it is Inet4Address }
                    ?: addresses.firstOrNull()

                if (resolved != null) {
                    val resolvedEndpoint = "${resolved.hostAddress}:$port"
                    Timber.d("WireGuard: resolved endpoint $endpoint -> $resolvedEndpoint")
                    peer.copy(endpoint = resolvedEndpoint)
                } else {
                    Timber.w("WireGuard: no IPs for $host, using hostname as-is")
                    peer
                }
            } catch (e: Exception) {
                Timber.w(e, "WireGuard: DNS resolution failed for $host, using as-is")
                peer
            }
        }

        return config.copy(peers = resolvedPeers)
    }

    private fun addIpv4Routes(builder: VpnService.Builder, excludeLan: Boolean) {
        if (excludeLan) {
            val routes = SubnetDecomposer.lanBypassRoutes
            Timber.d("Applying LAN bypass: adding ${routes.size} decomposed CIDR routes")
            for (route in routes) {
                builder.addRoute(route.ipString, route.prefix)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }
    }

    private fun addIpv6Routes(builder: VpnService.Builder, excludeLan: Boolean) {
        if (excludeLan && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute("2000::", 3)
        } else {
            builder.addRoute("::", 0)
        }
    }
}
