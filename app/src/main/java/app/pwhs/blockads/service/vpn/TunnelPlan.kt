package app.pwhs.blockads.service.vpn

/** An address or route prefix as passed to `VpnService.Builder`. */
data class Cidr(val address: String, val prefix: Int)

/** A route entry; [excluded] entries go through `excludeRoute` (API 33+). */
data class TunnelRoute(val cidr: Cidr, val excluded: Boolean = false)

/**
 * Everything [VpnTunnelBuilder] feeds into `VpnService.Builder`, in call order.
 * [fellBackToDirect] marks WireGuard mode that built a direct tunnel because the config was
 * missing or unusable (fails open today).
 */
data class TunnelPlan(
    val session: String,
    val mtu: Int,
    val addresses: List<Cidr>,
    val routes: List<TunnelRoute>,
    val dnsServers: List<String>,
    val disallowedApps: List<String>,
    val allowBypass: Boolean,
    val resolvedWgConfigJson: String,
    val isWireGuard: Boolean,
    val fellBackToDirect: Boolean,
)

/** Inputs to [TunnelPlanner.plan]: a prefs snapshot plus the platform facts it depends on. */
data class TunnelPlanInput(
    val routingMode: String,
    val wgConfigJson: String?,
    val excludeLan: Boolean,
    val allowAppBypass: Boolean,
    val ownPackage: String,
    val whitelistedApps: Set<String>,
    val sdkInt: Int,
)
