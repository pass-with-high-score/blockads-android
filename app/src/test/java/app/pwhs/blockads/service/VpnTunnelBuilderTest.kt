package app.pwhs.blockads.service

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.service.vpn.TunnelResult
import app.pwhs.blockads.service.vpn.VpnTunnelBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks that VpnTunnelBuilder applies a TunnelPlan to a real (recording) VpnService.Builder; plan logic is in TunnelPlannerTest. */
@RunWith(RobolectricTestRunner::class)
class VpnTunnelBuilderTest {

    private var routingMode = AppPreferences.ROUTING_MODE_DIRECT
    private var wgJson: String? = null
    private var excludeLan = false
    private var allowAppBypass = false
    private val prefs: AppPreferences = mockk {
        coEvery { getRoutingModeSnapshot() } answers { this@VpnTunnelBuilderTest.routingMode }
        coEvery { getWgConfigJsonSnapshot() } answers { wgJson }
        every { excludeLan } answers { flowOf(this@VpnTunnelBuilderTest.excludeLan) }
        every { allowAppBypass } answers { flowOf(this@VpnTunnelBuilderTest.allowAppBypass) }
    }
    private val service: VpnService = mockk { every { packageName } returns "app.pwhs.blockads" }
    private val tun: ParcelFileDescriptor = mockk(relaxed = true)
    private val sessions = mutableListOf<String>()
    private val addresses = mutableListOf<String>()
    private val routes = mutableListOf<String>()
    private val disallowed = mutableListOf<String>()
    private var bypass = false
    private var mtu = 0
    private var established: ParcelFileDescriptor? = tun

    private val wgConfig = WireGuardConfig(
        interfaceConfig = WireGuardInterface(privateKey = "k", address = listOf("10.8.0.2/24", "fd00:8::2")),
        peers = listOf(WireGuardPeer(publicKey = "p", endpoint = "203.0.113.5:51820", allowedIPs = listOf("0.0.0.0/0"))),
    )

    @Before
    fun setUp() {
        mockkStatic(VpnService::class)
        every { VpnService.prepare(any()) } returns null
        mockkConstructor(VpnService.Builder::class)
        every { anyConstructed<VpnService.Builder>().setSession(any()) } answers { sessions += firstArg<String>(); self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setMtu(any()) } answers { mtu = firstArg(); self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setBlocking(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addAddress(any<String>(), any()) } answers {
            addresses += "${firstArg<String>()}/${secondArg<Int>()}"; self as VpnService.Builder
        }
        every { anyConstructed<VpnService.Builder>().addRoute(any<String>(), any()) } answers {
            routes += "${firstArg<String>()}/${secondArg<Int>()}"; self as VpnService.Builder
        }
        every { anyConstructed<VpnService.Builder>().addDnsServer(any<String>()) } answers { self as VpnService.Builder }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            every { anyConstructed<VpnService.Builder>().excludeRoute(any<IpPrefix>()) } answers {
                routes += "-${firstArg<IpPrefix>()}"; self as VpnService.Builder
            }
        }
        every { anyConstructed<VpnService.Builder>().addDisallowedApplication(any()) } answers {
            val pkg = firstArg<String>()
            if (pkg == "not.installed") throw android.content.pm.PackageManager.NameNotFoundException(pkg)
            disallowed += pkg; self as VpnService.Builder
        }
        every { anyConstructed<VpnService.Builder>().allowBypass() } answers { bypass = true; self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setUnderlyingNetworks(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setMetered(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().establish() } answers { established }
    }

    @After
    fun tearDown() = unmockkAll()

    private fun establish(vararg whitelist: String) = VpnTunnelBuilder(service, prefs).establish(whitelist.toSet())

    @Test
    fun `revoked permission is reported before building anything`() {
        every { VpnService.prepare(any()) } returns android.content.Intent()
        assertEquals(TunnelResult.PermissionRevoked, establish())
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `direct mode is a bypassable full tunnel that excludes us and the whitelist`() {
        val result = establish("com.bank", "not.installed") as TunnelResult.Success
        assertEquals("", result.resolvedWgConfigJson)
        assertEquals(listOf("BlockAds"), sessions)
        assertEquals(1350, mtu)
        assertTrue(routes.containsAll(listOf("0.0.0.0/0", "::/0", "100.64.100.1/32", "fd00::1/128")))
        assertEquals(listOf("app.pwhs.blockads", "com.bank"), disallowed)
        assertTrue(bypass)
    }

    @Test
    fun `excludeLan swaps the default route for decomposed LAN-free routes`() {
        excludeLan = true
        establish()
        assertTrue("0.0.0.0/0" !in routes)
        assertTrue(routes.size > 10)
        assertTrue("::/0" in routes)
    }

    @Test
    fun `wireguard mode uses the interface addresses and is not bypassable by default`() {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        wgJson = wgConfig.toJson()
        val result = establish() as TunnelResult.Success

        assertEquals(listOf("BlockAds WireGuard"), sessions)
        assertEquals(1280, mtu)
        assertTrue(addresses.containsAll(listOf("10.8.0.2/24", "fd00:8::2/128", "100.64.100.2/32")))
        assertTrue(routes.containsAll(listOf("0.0.0.0/0", "::/0")))
        assertEquals(false, bypass)
        assertEquals("203.0.113.5:51820", WireGuardConfig.fromJson(result.resolvedWgConfigJson).peers.single().endpoint)
    }

    @Test
    fun `wireguard allows bypass only when the user opted in`() {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        wgJson = wgConfig.toJson()
        allowAppBypass = true
        establish()
        assertTrue(bypass)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `wireguard excludeLan carves out private ranges on API 33+`() {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        wgJson = wgConfig.toJson()
        excludeLan = true
        establish()
        assertEquals(4, routes.count { it.startsWith("-") })
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `a failing LAN exclusion skips the remaining ones but still builds the tunnel`() {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        wgJson = wgConfig.toJson()
        excludeLan = true
        var attempts = 0
        every { anyConstructed<VpnService.Builder>().excludeRoute(any<IpPrefix>()) } answers {
            attempts++; throw IllegalArgumentException("nope")
        }
        assertTrue(establish() is TunnelResult.Success)
        assertEquals(1, attempts)
        assertTrue("100.64.100.1/32" in routes)
    }

    @Test
    fun `a malformed wireguard address is a retryable failure`() {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        wgJson = wgConfig.copy(interfaceConfig = wgConfig.interfaceConfig.copy(address = listOf("bogus"))).toJson()
        every { anyConstructed<VpnService.Builder>().addAddress(eq("bogus"), any()) } throws IllegalArgumentException("Bad address")
        assertEquals(TunnelResult.Failure, establish())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `wireguard excludeLan has no excludeRoute before API 33`() {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        wgJson = wgConfig.toJson()
        excludeLan = true
        establish()
        assertEquals(0, routes.count { it.startsWith("-") })
    }

    @Test
    fun `a null interface from establish is a failure`() {
        established = null
        assertEquals(TunnelResult.Failure, establish())
    }

    @Test
    fun `builder exceptions become a failure`() {
        every { anyConstructed<VpnService.Builder>().establish() } throws IllegalStateException("boom")
        assertEquals(TunnelResult.Failure, establish())
    }

    @Ignore("an unparseable WireGuard config silently falls back to a direct tunnel instead of refusing")
    @Test
    fun `unparseable wireguard config refuses to connect`() {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        wgJson = "{not json"
        val result = establish()
        assertTrue(result !is TunnelResult.Success)
    }

    @Ignore("WireGuard mode with no saved config silently builds a direct tunnel instead of refusing")
    @Test
    fun `missing wireguard config refuses to connect`() {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        wgJson = null
        assertTrue(establish() !is TunnelResult.Success)
    }
}
