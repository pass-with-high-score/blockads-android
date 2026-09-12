package app.pwhs.blockads.service.vpn

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.utils.SubnetDecomposer
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
    data object Failure : TunnelResult
}

class VpnTunnelBuilder(
    private val vpnService: VpnService,
    private val appPrefs: AppPreferences
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
            val wgConfig: WireGuardConfig? =
                if (routingMode == AppPreferences.ROUTING_MODE_WIREGUARD) {
                    val json = runBlocking { appPrefs.getWgConfigJsonSnapshot() }
                    json?.let {
                        try {
                            val parsed = WireGuardConfig.fromJson(it)
                            val resolved = resolveWireGuardEndpoints(parsed)
                            resolvedWgConfigJson = resolved.toJson()
                            resolved
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to parse WireGuard config, falling back to direct")
                            null
                        }
                    }
                } else null

            val builder = if (wgConfig != null) {
                Timber.d("Establishing VPN in WireGuard mode")
                val b = vpnService.Builder()
                    .setSession("BlockAds WireGuard")
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

                val excludeLan = runBlocking { appPrefs.excludeLan.first() }
                addIpv4Routes(b, excludeLan)
                b.addRoute("::", 0)

                if (excludeLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        b.excludeRoute(IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
                        b.excludeRoute(IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
                        b.excludeRoute(IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
                        b.excludeRoute(IpPrefix(InetAddress.getByName("169.254.0.0"), 16))
                        Timber.d("LAN excluded from WireGuard VPN routes")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to exclude LAN routes")
                    }
                }

                b.addAddress("100.64.100.2", 32)
                b.addDnsServer("100.64.100.1")
                b.addRoute("100.64.100.1", 32)
                b
            } else {
                val excludeLan = runBlocking { appPrefs.excludeLan.first() }
                Timber.d("Establishing VPN in direct mode (fullTunnel=true, excludeLan=$excludeLan)")
                val b = vpnService.Builder()
                    .setSession("BlockAds")
                    .addAddress("100.64.100.2", 32)
                    .addRoute("100.64.100.1", 32)
                    .addDnsServer("100.64.100.1")
                    .addAddress("fd00::2", 128)
                    .addRoute("fd00::1", 128)
                    .addDnsServer("fd00::1")
                    .setBlocking(false)
                    .setMtu(1350)
                addIpv4Routes(b, excludeLan)
                b
            }

            try {
                builder.addDisallowedApplication(vpnService.packageName)
            } catch (e: Exception) {
                Timber.w(e, "Could not exclude self from VPN")
            }

            for (appPackage in whitelistedApps) {
                try {
                    builder.addDisallowedApplication(appPackage)
                    Timber.d("Excluded from VPN: $appPackage")
                } catch (e: Exception) {
                    Timber.w(e, "Could not exclude $appPackage from VPN")
                }
            }

            if (wgConfig == null) {
                builder.allowBypass()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setUnderlyingNetworks(null)
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd != null) {
                try {
                    val flags = android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_GETFL, 0)
                    android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_SETFL, flags or android.system.OsConstants.O_NONBLOCK)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to set TUN O_NONBLOCK via fcntl")
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
}
