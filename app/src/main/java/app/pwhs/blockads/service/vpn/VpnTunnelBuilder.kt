package app.pwhs.blockads.service.vpn

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardConfig
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
            val routingMode = runBlocking { appPrefs.getRoutingModeSnapshot() }
            val input = TunnelPlanInput(
                routingMode = routingMode,
                wgConfigJson = if (routingMode == AppPreferences.ROUTING_MODE_WIREGUARD) {
                    runBlocking { appPrefs.getWgConfigJsonSnapshot() }
                } else null,
                excludeLan = runBlocking { appPrefs.excludeLan.first() },
                allowAppBypass = runBlocking { appPrefs.allowAppBypass.first() },
                ownPackage = vpnService.packageName,
                whitelistedApps = whitelistedApps,
                sdkInt = Build.VERSION.SDK_INT,
            )
            Timber.d("Tunnel inputs: routingMode=$routingMode, excludeLan=${input.excludeLan}, allowAppBypass=${input.allowAppBypass}")
            val plan = TunnelPlanner.plan(input, ::resolveWireGuardEndpoints)
            val pfd = apply(plan).establish()
            if (pfd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val flags = android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_GETFL, 0)
                        android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_SETFL, flags or android.system.OsConstants.O_NONBLOCK)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to set TUN O_NONBLOCK via fcntl")
                    }
                }
                TunnelResult.Success(pfd, plan.resolvedWgConfigJson)
            } else {
                Timber.e("Failed to establish VPN interface")
                TunnelResult.Failure
            }
        } catch (e: Exception) {
            Timber.e(e, "Error establishing VPN")
            TunnelResult.Failure
        }
    }

    private fun apply(plan: TunnelPlan): VpnService.Builder {
        Timber.d("Establishing VPN: session=${plan.session}, fellBackToDirect=${plan.fellBackToDirect}")
        val builder = vpnService.Builder()
            .setSession(plan.session)
            .setBlocking(false)
            .setMtu(plan.mtu)
        for (addr in plan.addresses) builder.addAddress(addr.address, addr.prefix)

        var excludeFailed = false
        for (route in plan.routes) {
            if (!route.excluded) {
                builder.addRoute(route.cidr.address, route.cidr.prefix)
            } else if (!excludeFailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    builder.excludeRoute(IpPrefix(InetAddress.getByName(route.cidr.address), route.cidr.prefix))
                } catch (e: Exception) {
                    // Exclusions are best-effort as a group: the first failure skips the rest.
                    excludeFailed = true
                    Timber.w(e, "Failed to exclude LAN routes")
                }
            }
        }
        for (dns in plan.dnsServers) builder.addDnsServer(dns)

        for (appPackage in plan.disallowedApps) {
            try {
                builder.addDisallowedApplication(appPackage)
            } catch (e: Exception) {
                Timber.w(e, "Could not exclude $appPackage from VPN")
            }
        }
        if (plan.allowBypass) builder.allowBypass()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setUnderlyingNetworks(null)
            builder.setMetered(false)
        }
        return builder
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
}
