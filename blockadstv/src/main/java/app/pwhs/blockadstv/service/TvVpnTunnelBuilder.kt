package app.pwhs.blockadstv.service

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.utils.NetworkInfoHelper
import app.pwhs.blockadstv.utils.SubnetDecomposer
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Builds and establishes the Android TUN interface for [TvVpnService],
 * applying IPv6 blackhole prevention and LAN bypass routing rules.
 */
class TvVpnTunnelBuilder(
    private val vpnService: VpnService,
    private val hasIpv6Provider: () -> Boolean = { NetworkInfoHelper.hasRoutableIpv6(vpnService) },
) {

    fun establish(
        whitelistedApps: Set<String>,
        isProxyVpn: Boolean,
        excludeLan: Boolean,
        preferredIpType: String,
        proxyHost: String? = null,
    ): ParcelFileDescriptor? {
        if (VpnService.prepare(vpnService) != null) {
            Timber.e("VPN not prepared or permission revoked")
            return null
        }

        val allowIpv6 = when (preferredIpType) {
            TvPreferences.PREFERRED_IP_IPV4 -> false
            TvPreferences.PREFERRED_IP_IPV6 -> true
            else -> hasIpv6Provider()
        }
        val allowIpv4 = preferredIpType != TvPreferences.PREFERRED_IP_IPV6

        Timber.d(
            "Establishing TV VPN (isProxyVpn=$isProxyVpn, excludeLan=$excludeLan, " +
                "preferredIp=$preferredIpType, allowIpv4=$allowIpv4, allowIpv6=$allowIpv6)"
        )

        return try {
            val builder = vpnService.Builder()
                .setSession(if (isProxyVpn) "BlockAds TV Proxy" else "BlockAds TV")
                .addAddress("100.64.100.2", 32)
                .addRoute("100.64.100.1", 32)
                .addDnsServer("100.64.100.1")
                .setBlocking(false)
                .setMtu(1350)

            if (allowIpv6) {
                builder.addAddress("fd00::2", 128)
                builder.addRoute("fd00::1", 128)
                builder.addDnsServer("fd00::1")
            }

            if (isProxyVpn) {
                if (allowIpv4) {
                    addIpv4Routes(builder, excludeLan)
                }
                if (allowIpv6) {
                    addIpv6Routes(builder, excludeLan)
                }
                if (excludeLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    excludeLanPrefixes(builder, allowIpv4, allowIpv6)
                }
                if (!proxyHost.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    excludeProxyServer(builder, proxyHost.trim())
                }
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
                    Timber.w(e, "Could not exclude $appPackage")
                }
            }

            builder.allowBypass()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setUnderlyingNetworks(null)
                // Inherit meteredness from underlying network so Wi-Fi-only apps keep working.
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        vpnService.setUnderlyingNetworks(null)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to reset underlying networks on VpnService")
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val fd = pfd.fileDescriptor
                        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
                        Timber.d("TV TUN file descriptor set to O_NONBLOCK successfully")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to enforce O_NONBLOCK on TV TUN fd")
                    }
                }
            }
            Timber.d("VPN interface established: ${pfd != null}")
            pfd
        } catch (e: Exception) {
            Timber.e(e, "Error establishing VPN")
            null
        }
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

    private fun excludeLanPrefixes(
        builder: VpnService.Builder,
        allowIpv4: Boolean,
        allowIpv6: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            if (allowIpv4) {
                builder.excludeRoute(IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
                builder.excludeRoute(IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
                builder.excludeRoute(IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
                builder.excludeRoute(IpPrefix(InetAddress.getByName("169.254.0.0"), 16))
            }
            if (allowIpv6) {
                // Do NOT exclude fc00::/7 because fd00::1 (synthetic DNS) is within fc00::/7.
                builder.excludeRoute(IpPrefix(InetAddress.getByName("fe80::"), 10))
            }
            Timber.d("LAN excluded from TV VPN routes")
        } catch (e: Exception) {
            Timber.w(e, "Failed to exclude LAN routes")
        }
    }

    private fun excludeProxyServer(builder: VpnService.Builder, proxyHost: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            for (addr in InetAddress.getAllByName(proxyHost)) {
                val prefix = if (addr is Inet4Address) 32 else 128
                builder.excludeRoute(IpPrefix(addr, prefix))
                Timber.d("Proxy server $addr excluded from TV VPN routes")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to exclude proxy server $proxyHost from TV VPN routes")
        }
    }
}
