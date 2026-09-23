package app.pwhs.blockads.service.vpn

import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.utils.SubnetDecomposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class TunnelPlannerTest {

    private val wgConfig = WireGuardConfig(
        interfaceConfig = WireGuardInterface(privateKey = "k", address = listOf("10.8.0.2/24", "fd00:8::2", "10.9.0.2")),
        peers = listOf(WireGuardPeer(publicKey = "p", endpoint = "vpn.example:51820", allowedIPs = listOf("10.8.0.0/24"))),
    )

    private fun input(
        routingMode: String = AppPreferences.ROUTING_MODE_DIRECT,
        wgConfigJson: String? = null,
        excludeLan: Boolean = false,
        allowAppBypass: Boolean = false,
        whitelist: Set<String> = emptySet(),
        sdkInt: Int = 34,
    ) = TunnelPlanInput(routingMode, wgConfigJson, excludeLan, allowAppBypass, "app.pwhs.blockads", whitelist, sdkInt)

    private fun wg(excludeLan: Boolean = false, allowAppBypass: Boolean = false, sdkInt: Int = 34) =
        input(AppPreferences.ROUTING_MODE_WIREGUARD, wgConfig.toJson(), excludeLan, allowAppBypass, sdkInt = sdkInt)

    private val decomposedLan = SubnetDecomposer.lanBypassRoutes.map { TunnelRoute(Cidr(it.ipString, it.prefix)) }

    @Test
    fun `direct mode is a dual-stack bypassable full tunnel`() {
        val plan = TunnelPlanner.plan(input())
        assertEquals(
            TunnelPlan(
                session = "BlockAds",
                mtu = 1350,
                addresses = listOf(Cidr("100.64.100.2", 32), Cidr("fd00::2", 128)),
                routes = listOf(
                    TunnelRoute(Cidr("100.64.100.1", 32)),
                    TunnelRoute(Cidr("fd00::1", 128)),
                    TunnelRoute(Cidr("::", 0)),
                    TunnelRoute(Cidr("0.0.0.0", 0)),
                ),
                dnsServers = listOf("100.64.100.1", "fd00::1"),
                disallowedApps = listOf("app.pwhs.blockads"),
                allowBypass = true,
                resolvedWgConfigJson = "",
                isWireGuard = false,
                fellBackToDirect = false,
            ),
            plan,
        )
    }

    @Test
    fun `direct excludeLan swaps the IPv4 default for decomposed routes and never uses excludeRoute`() {
        for (sdk in listOf(29, 32, 33, 35)) {
            val routes = TunnelPlanner.plan(input(excludeLan = true, sdkInt = sdk)).routes
            assertEquals(3 + decomposedLan.size, routes.size)
            assertEquals(decomposedLan, routes.drop(3))
            assertTrue(routes.none { it.excluded })
            assertTrue(TunnelRoute(Cidr("0.0.0.0", 0)) !in routes)
        }
    }

    @Test
    fun `excludeLan leaves IPv6 LAN inside the tunnel in both modes`() {
        for (plan in listOf(TunnelPlanner.plan(input(excludeLan = true)), TunnelPlanner.plan(wg(excludeLan = true)))) {
            assertTrue(TunnelRoute(Cidr("::", 0)) in plan.routes)
            assertTrue(plan.routes.none { it.cidr.address.contains(":") && it.excluded })
        }
    }

    @Test
    fun `root routing mode plans a direct tunnel`() {
        assertEquals(TunnelPlanner.plan(input()), TunnelPlanner.plan(input(routingMode = AppPreferences.ROUTING_MODE_ROOT)))
    }

    @Test
    fun `wireguard uses interface addresses with default host prefixes plus the DNS shim`() {
        val plan = TunnelPlanner.plan(wg())
        assertEquals("BlockAds WireGuard", plan.session)
        assertEquals(1280, plan.mtu)
        assertTrue(plan.isWireGuard)
        assertFalse(plan.fellBackToDirect)
        assertEquals(
            listOf(Cidr("10.8.0.2", 24), Cidr("fd00:8::2", 128), Cidr("10.9.0.2", 32), Cidr("100.64.100.2", 32)),
            plan.addresses,
        )
        assertEquals(listOf("100.64.100.1"), plan.dnsServers)
    }

    @Test
    fun `wireguard routes ignore peer AllowedIPs and always full-tunnel`() {
        assertEquals(
            listOf(
                TunnelRoute(Cidr("0.0.0.0", 0)),
                TunnelRoute(Cidr("::", 0)),
                TunnelRoute(Cidr("100.64.100.1", 32)),
            ),
            TunnelPlanner.plan(wg()).routes,
        )
    }

    @Test
    fun `wireguard excludeLan carves out private ranges only on API 33+`() {
        val lan = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")
        for (sdk in 33..36) {
            val routes = TunnelPlanner.plan(wg(excludeLan = true, sdkInt = sdk)).routes
            assertEquals(decomposedLan, routes.take(decomposedLan.size))
            assertEquals(TunnelRoute(Cidr("::", 0)), routes[decomposedLan.size])
            assertEquals(lan, routes.filter { it.excluded }.map { "${it.cidr.address}/${it.cidr.prefix}" })
            assertEquals(TunnelRoute(Cidr("100.64.100.1", 32)), routes.last())
        }
        for (sdk in 26..32) {
            val routes = TunnelPlanner.plan(wg(excludeLan = true, sdkInt = sdk)).routes
            assertTrue(routes.none { it.excluded })
            assertEquals(decomposedLan.size + 2, routes.size)
        }
    }

    @Test
    fun `allowBypass is always on in direct mode and opt-in for wireguard`() {
        for (optIn in listOf(false, true)) {
            assertTrue(TunnelPlanner.plan(input(allowAppBypass = optIn)).allowBypass)
            assertEquals(optIn, TunnelPlanner.plan(wg(allowAppBypass = optIn)).allowBypass)
        }
    }

    @Test
    fun `own package is disallowed first, then the whitelist in order, duplicates kept`() {
        val plan = TunnelPlanner.plan(input(whitelist = linkedSetOf("com.bank", "app.pwhs.blockads", "org.maps")))
        assertEquals(listOf("app.pwhs.blockads", "com.bank", "app.pwhs.blockads", "org.maps"), plan.disallowedApps)
        val wgPlan = TunnelPlanner.plan(wg().copy(whitelistedApps = setOf("com.bank")))
        assertEquals(listOf("app.pwhs.blockads", "com.bank"), wgPlan.disallowedApps)
    }

    @Test
    fun `resolved endpoints end up in the engine config json`() {
        val plan = TunnelPlanner.plan(wg()) { cfg ->
            cfg.copy(peers = cfg.peers.map { it.copy(endpoint = "203.0.113.5:51820") })
        }
        assertEquals("203.0.113.5:51820", WireGuardConfig.fromJson(plan.resolvedWgConfigJson).peers.single().endpoint)
    }

    @Test
    fun `direct mode never reads or resolves a wireguard config`() {
        val plan = TunnelPlanner.plan(input(wgConfigJson = wgConfig.toJson())) { error("must not resolve") }
        assertEquals("", plan.resolvedWgConfigJson)
        assertFalse(plan.isWireGuard)
    }

    @Test
    fun `malformed interface address is passed through verbatim for the builder to reject`() {
        val bad = wgConfig.copy(interfaceConfig = wgConfig.interfaceConfig.copy(address = listOf("not-an-ip/xx")))
        val plan = TunnelPlanner.plan(input(AppPreferences.ROUTING_MODE_WIREGUARD, bad.toJson()))
        assertEquals(Cidr("not-an-ip", 32), plan.addresses.first())
    }

    @Ignore("known bug: a malformed WireGuard address is only rejected by the builder, which is retried instead of failing fast")
    @Test
    fun `malformed interface address fails fast at plan time`() {
        val bad = wgConfig.copy(interfaceConfig = wgConfig.interfaceConfig.copy(address = listOf("not-an-ip/xx")))
        val plan = runCatching { TunnelPlanner.plan(input(AppPreferences.ROUTING_MODE_WIREGUARD, bad.toJson())) }
        assertTrue(plan.isFailure)
    }

    @Test
    fun `unusable wireguard configs currently fall back to a direct tunnel`() {
        val cases = listOf<Pair<String?, (WireGuardConfig) -> WireGuardConfig>>(
            null to { it },
            "{not json" to { it },
            wgConfig.toJson() to { throw IllegalStateException("resolver blew up") },
        )
        for ((json, resolver) in cases) {
            val plan = TunnelPlanner.plan(input(AppPreferences.ROUTING_MODE_WIREGUARD, json), resolver)
            assertTrue(plan.fellBackToDirect)
            assertEquals(TunnelPlanner.plan(input()).copy(fellBackToDirect = true), plan)
        }
    }

    @Ignore("known bug: WireGuard mode with an unparseable or missing config fails open to a direct tunnel")
    @Test
    fun `unusable wireguard config refuses to build a tunnel`() {
        for (json in listOf(null, "{not json")) {
            val plan = runCatching { TunnelPlanner.plan(input(AppPreferences.ROUTING_MODE_WIREGUARD, json)) }
            assertTrue(plan.isFailure)
        }
    }
}
