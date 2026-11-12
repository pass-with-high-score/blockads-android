package app.pwhs.blockadstv.service

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.pwhs.blockadstv.data.datastore.WireGuardPreferences
import app.pwhs.blockadstv.data.entities.WireGuardConfig
import app.pwhs.blockadstv.data.geoip.GeoIpManager
import app.pwhs.blockadstv.utils.SubnetDecomposer
import app.pwhs.blockadstv.utils.WgRouting
import app.pwhs.blockadstv.utils.wgRoutingFromPeers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.InetAddress

data class PreparedWireGuardTunnel(
    val config: WireGuardConfig,
    val routing: WgRouting,
    val configJson: String,
)

object TvWireGuardTunnelHelper {

    suspend fun prepare(wireGuardPrefs: WireGuardPreferences): PreparedWireGuardTunnel? {
        val rawJson = wireGuardPrefs.getWgConfigJsonSnapshot() ?: return null
        val parsed = try {
            WireGuardConfig.fromJson(rawJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse WireGuard config, falling back to direct")
            return null
        }

        val resolved = parsed.resolveEndpoints()
        val routing = wgRoutingFromPeers(resolved.peers)
        routing.issue?.let { issue ->
            Timber.e("WireGuard: refusing to connect, ${issue.toDisplayMessage()}")
            return null
        }

        val normalizedJson = resolved.copy(peers = routing.peers).toJson()
        return PreparedWireGuardTunnel(
            config = resolved,
            routing = routing,
            configJson = normalizedJson,
        )
    }

    fun establishTun(
        vpnService: VpnService,
        prepared: PreparedWireGuardTunnel,
        whitelistedApps: Set<String>,
        excludeLan: Boolean,
    ): ParcelFileDescriptor? {
        if (VpnService.prepare(vpnService) != null) {
            Timber.e("VPN not prepared or permission revoked")
            return null
        }

        return try {
            val builder = vpnService.Builder()
                .setSession("BlockAds TV WireGuard")
                .setBlocking(false)
                .setMtu(1280)

            for (addr in prepared.config.interfaceConfig.address) {
                val parts = addr.split("/")
                val ip = parts[0]
                val prefix = parts.getOrNull(1)?.toIntOrNull()
                if (ip.contains(":")) {
                    builder.addAddress(ip, prefix ?: 128)
                } else {
                    builder.addAddress(ip, prefix ?: 32)
                }
            }

            addWireGuardRoutes(builder, prepared, excludeLan)

            builder.addAddress("100.64.100.2", 32)
            builder.addDnsServer("100.64.100.1")
            builder.addRoute("100.64.100.1", 32)
            builder.addAddress("fd00::2", 128)
            builder.addDnsServer("fd00::1")
            builder.addRoute("fd00::1", 128)

            runCatching { builder.addDisallowedApplication(vpnService.packageName) }
            for (appPackage in whitelistedApps) {
                runCatching { builder.addDisallowedApplication(appPackage) }
            }

            builder.allowBypass()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setUnderlyingNetworks(null)
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd != null) {
                configureEstablishedTun(vpnService, pfd)
            }
            pfd
        } catch (e: Exception) {
            Timber.e(e, "Error establishing WireGuard TV VPN")
            null
        }
    }

    private fun addWireGuardRoutes(
        builder: VpnService.Builder,
        prepared: PreparedWireGuardTunnel,
        excludeLan: Boolean,
    ) {
        val routing = prepared.routing
        for (route in routing.tunRoutes) {
            if (excludeLan && !route.isIpv6 && route.prefixLength == 0) {
                for (r in SubnetDecomposer.lanBypassRoutes) {
                    builder.addRoute(r.ipString, r.prefix)
                }
            } else if (excludeLan && route.isIpv6 && route.prefixLength == 0 &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            ) {
                builder.addRoute("2000::", 3)
            } else {
                builder.addRoute(route.address, route.prefixLength)
            }
        }

        if (routing.isFullTunnel && excludeLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val wgAddrs = prepared.config.interfaceConfig.address.map { it.split("/")[0] }
                if (wgAddrs.none { it.startsWith("10.") }) {
                    builder.excludeRoute(IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
                }
                if (wgAddrs.none { addr -> (16..31).any { addr.startsWith("172.$it.") } }) {
                    builder.excludeRoute(IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
                }
                if (wgAddrs.none { it.startsWith("192.168.") }) {
                    builder.excludeRoute(IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
                }
                builder.excludeRoute(IpPrefix(InetAddress.getByName("169.254.0.0"), 16))
                builder.excludeRoute(IpPrefix(InetAddress.getByName("fe80::"), 10))
            } catch (e: Exception) {
                Timber.w(e, "Failed to exclude LAN routes for WireGuard")
            }
        }
    }

    private fun configureEstablishedTun(vpnService: VpnService, pfd: ParcelFileDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { vpnService.setUnderlyingNetworks(null) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val fd = pfd.fileDescriptor
                val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            } catch (e: Exception) {
                Timber.w(e, "Failed to enforce O_NONBLOCK on WireGuard TV TUN fd")
            }
        }
    }

    fun observeGeoIpUpdates(
        scope: CoroutineScope,
        geoIpManager: GeoIpManager,
        goTunnelAdapter: GoTunnelAdapter,
    ) {
        scope.launch {
            geoIpManager.updateEvents.collectLatest {
                Timber.d("TV GeoIP database updated, reloading into Go engine")
                goTunnelAdapter.loadGeoIPDatabase()
            }
        }
    }
}
