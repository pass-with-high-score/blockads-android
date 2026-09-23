package app.pwhs.blockads.service.vpn

import android.os.Build
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.utils.SubnetDecomposer
import timber.log.Timber

/** Pure computation of the tunnel shape; endpoint DNS resolution is injected so tests stay offline. */
object TunnelPlanner {

    private const val SESSION_DIRECT = "BlockAds"
    private const val SESSION_WIREGUARD = "BlockAds WireGuard"
    private const val MTU_DIRECT = 1350
    private const val MTU_WIREGUARD = 1280
    private val LAN_EXCLUDES = listOf(
        Cidr("10.0.0.0", 8),
        Cidr("172.16.0.0", 12),
        Cidr("192.168.0.0", 16),
        Cidr("169.254.0.0", 16),
    )

    fun plan(
        input: TunnelPlanInput,
        resolveEndpoints: (WireGuardConfig) -> WireGuardConfig = { it },
    ): TunnelPlan {
        val isWireGuardMode = input.routingMode == AppPreferences.ROUTING_MODE_WIREGUARD
        var resolvedWgConfigJson = ""
        val wgConfig: WireGuardConfig? = if (isWireGuardMode) {
            input.wgConfigJson?.let {
                try {
                    val resolved = resolveEndpoints(WireGuardConfig.fromJson(it))
                    resolvedWgConfigJson = resolved.toJson()
                    resolved
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse WireGuard config, falling back to direct")
                    null
                }
            }
        } else null

        val base = if (wgConfig != null) wireGuardPlan(wgConfig, input) else directPlan(input)
        return base.copy(
            disallowedApps = listOf(input.ownPackage) + input.whitelistedApps,
            // A non-bypassable tunnel refuses an app's explicit bind to an
            // underlying network, which is what breaks wireless Android Auto.
            allowBypass = wgConfig == null || input.allowAppBypass,
            resolvedWgConfigJson = resolvedWgConfigJson,
            fellBackToDirect = isWireGuardMode && wgConfig == null,
        )
    }

    private fun wireGuardPlan(config: WireGuardConfig, input: TunnelPlanInput): TunnelPlan {
        val addresses = config.interfaceConfig.address.map { addr ->
            val parts = addr.split("/")
            val ip = parts[0]
            val prefix = parts.getOrNull(1)?.toIntOrNull()
            Cidr(ip, prefix ?: if (ip.contains(":")) 128 else 32)
        } + Cidr("100.64.100.2", 32)

        val excludes = if (input.excludeLan && input.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            LAN_EXCLUDES.map { TunnelRoute(it, excluded = true) }
        } else emptyList()
        val routes = ipv4Routes(input.excludeLan) +
            TunnelRoute(Cidr("::", 0)) +
            excludes +
            TunnelRoute(Cidr("100.64.100.1", 32))

        return TunnelPlan(
            session = SESSION_WIREGUARD,
            mtu = MTU_WIREGUARD,
            addresses = addresses,
            routes = routes,
            dnsServers = listOf("100.64.100.1"),
            disallowedApps = emptyList(),
            allowBypass = false,
            resolvedWgConfigJson = "",
            isWireGuard = true,
            fellBackToDirect = false,
        )
    }

    private fun directPlan(input: TunnelPlanInput): TunnelPlan = TunnelPlan(
        session = SESSION_DIRECT,
        mtu = MTU_DIRECT,
        addresses = listOf(Cidr("100.64.100.2", 32), Cidr("fd00::2", 128)),
        routes = listOf(
            TunnelRoute(Cidr("100.64.100.1", 32)),
            TunnelRoute(Cidr("fd00::1", 128)),
            TunnelRoute(Cidr("::", 0)),
        ) + ipv4Routes(input.excludeLan),
        dnsServers = listOf("100.64.100.1", "fd00::1"),
        disallowedApps = emptyList(),
        allowBypass = true,
        resolvedWgConfigJson = "",
        isWireGuard = false,
        fellBackToDirect = false,
    )

    private fun ipv4Routes(excludeLan: Boolean): List<TunnelRoute> =
        if (excludeLan) {
            SubnetDecomposer.lanBypassRoutes.map { TunnelRoute(Cidr(it.ipString, it.prefix)) }
        } else {
            listOf(TunnelRoute(Cidr("0.0.0.0", 0)))
        }
}
