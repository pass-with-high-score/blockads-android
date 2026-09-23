package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.ParcelFileDescriptor
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.vpn.StartupConfig
import app.pwhs.blockads.service.vpn.VpnEngineCoordinator
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class VpnEngineCoordinatorTest {

    private var providerId: String? = "cloudflare"
    private var firewallEnabled = false
    private var routingMode = AppPreferences.ROUTING_MODE_DIRECT
    private var savedWgJson: String? = null
    private val prefs: AppPreferences = mockk {
        every { upstreamDns } returns flowOf("1.1.1.1")
        every { fallbackDns } returns flowOf("9.9.9.9")
        every { dnsResponseType } returns flowOf("NXDOMAIN")
        every { dnsProtocol } returns flowOf(DnsProtocol.DOH)
        every { dohUrl } returns flowOf("https://dns.test/q")
        every { safeSearchEnabled } returns flowOf(true)
        every { youtubeRestrictedMode } returns flowOf(false)
        every { firewallEnabled } answers { flowOf(this@VpnEngineCoordinatorTest.firewallEnabled) }
        every { dnsProviderId } answers { flowOf(providerId) }
        every { splitDnsZones } returns flowOf("lan")
        every { getSelectedBrowsersSnapshot() } returns setOf("com.android.chrome")
        coEvery { getWhitelistedAppsSnapshot() } returns setOf("com.bank")
        coEvery { getRoutingModeSnapshot() } answers { this@VpnEngineCoordinatorTest.routingMode }
        coEvery { getWgConfigJsonSnapshot() } answers { savedWgJson }
        coEvery { getFilterHttp3Snapshot() } returns false
        coEvery { getBlockDohBypassSnapshot() } returns true
    }
    private val domainCount = MutableStateFlow(100)
    private val filterRepo: FilterListRepository = mockk(relaxed = true) {
        every { domainCountFlow } returns this@VpnEngineCoordinatorTest.domainCount
        coEvery { loadAllEnabledFilters() } returns Result.success(100)
    }
    private val firewallDao: FirewallRuleDao = mockk { coEvery { getEnabledRules() } returns emptyList() }
    private var systemDns = listOf("192.168.1.1")
    private val network: Network = mockk()
    private val cm: ConnectivityManager = mockk {
        every { activeNetwork } returns network
        every { getLinkProperties(network) } answers { linkProps(systemDns) }
    }
    private val context: Context = mockk { every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm }
    private val adapter: GoTunnelAdapter = mockk(relaxed = true)
    private val tun: ParcelFileDescriptor = mockk()
    private val coordinator = VpnEngineCoordinator(context, prefs, filterRepo, firewallDao)

    private fun linkProps(dns: List<String>): LinkProperties = mockk {
        every { dnsServers } returns dns.map { InetAddress.getByName(it) }
    }

    private fun config(providerId: String? = "cloudflare") = StartupConfig(
        upstreamDns = "1.1.1.1", fallbackDns = "9.9.9.9", dnsResponseType = "REFUSED", dnsProtocol = DnsProtocol.DOT,
        dohUrl = "", whitelistedApps = emptySet(), safeSearchEnabled = false, youtubeRestrictedMode = true,
        firewallEnabled = false, dnsProviderId = providerId, firewallManager = null,
    )

    @Test
    fun `startup loads filters in order then snapshots prefs`() = runTest {
        val cfg = coordinator.prepareStartupConfig()

        coVerifyOrder {
            filterRepo.loadWhitelist()
            filterRepo.loadCustomRules()
            filterRepo.seedDefaultsIfNeeded()
            filterRepo.fetchAndSyncRemoteFilterLists()
            filterRepo.loadAllEnabledFilters()
        }
        assertEquals("1.1.1.1", cfg.upstreamDns)
        assertEquals(DnsProtocol.DOH, cfg.dnsProtocol)
        assertEquals(setOf("com.bank"), cfg.whitelistedApps)
        assertEquals("cloudflare", cfg.dnsProviderId)
        assertNull(cfg.firewallManager)
    }

    @Test
    fun `firewall manager is built only when the firewall is on`() = runTest {
        firewallEnabled = true
        val cfg = coordinator.prepareStartupConfig()
        assertTrue(cfg.firewallEnabled)
        assertNotNull(cfg.firewallManager)
    }

    @Test
    fun `engine gets the configured DNS, block response, safe search and split zones`() = runTest {
        coordinator.configureEngine(adapter, config())
        verify { adapter.configureDns("DOT", "1.1.1.1", "9.9.9.9", "") }
        verify { adapter.setBlockResponseType("REFUSED") }
        verify { adapter.configureSafeSearch(false, true) }
        verify { adapter.setSplitDNSZones("lan") }
    }

    @Test
    fun `system provider uses the network's resolver over plain DNS`() = runTest {
        coordinator.configureEngine(adapter, config(providerId = "system"))
        verify { adapter.configureDns("PLAIN", "192.168.1.1", "9.9.9.9", "") }

        systemDns = emptyList()
        coordinator.configureEngine(adapter, config(providerId = "system"))
        verify { adapter.configureDns("PLAIN", "8.8.8.8", "9.9.9.9", "") }
    }

    @Test
    fun `wireguard mode passes the resolved config, falling back to the saved one`() = runTest {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        savedWgJson = "{\"saved\":1}"
        coordinator.startTunnel(adapter, tun, "{\"resolved\":1}", httpsFilteringEnabled = false, certDir = "/c") { true }
        verify { adapter.start(tun, "{\"resolved\":1}", false, setOf("com.android.chrome"), "/c", false, true, any()) }

        coordinator.startTunnel(adapter, tun, "", httpsFilteringEnabled = false, certDir = "/c") { true }
        verify { adapter.start(tun, "{\"saved\":1}", false, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `direct mode ignores any WireGuard config and https forces HTTP3 filtering`() = runTest {
        savedWgJson = "{\"saved\":1}"
        coordinator.startTunnel(adapter, tun, "{\"resolved\":1}", httpsFilteringEnabled = true, certDir = "/c") { true }
        verify { adapter.start(tun, "", true, any(), "/c", true, true, any()) }
    }

    @Ignore("WireGuard mode with no usable config starts the direct full-tunnel engine instead of failing closed")
    @Test
    fun `wireguard mode without a config does not start in direct mode`() = runTest {
        routingMode = AppPreferences.ROUTING_MODE_WIREGUARD
        savedWgJson = null
        runCatching { coordinator.startTunnel(adapter, tun, "", httpsFilteringEnabled = false, certDir = "") { true } }
        verify(exactly = 0) { adapter.start(any(), "", any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `link changes hot-reload DNS only for the system provider`() = runTest {
        coordinator.handleLinkPropertiesChanged(adapter, linkProps(listOf("10.0.0.1")))
        verify(exactly = 0) { adapter.configureDns(any(), any(), any(), any()) }

        providerId = "system"
        coordinator.handleLinkPropertiesChanged(adapter, linkProps(listOf("10.0.0.1")))
        verify { adapter.configureDns("PLAIN", "10.0.0.1", "9.9.9.9", "https://dns.test/q") }
        coordinator.handleLinkPropertiesChanged(adapter, null)
        verify { adapter.configureDns("PLAIN", "8.8.8.8", "9.9.9.9", "https://dns.test/q") }
    }

    @Test
    fun `filter watcher reloads tries when the domain count changes`() = runTest {
        coordinator.startFilterUpdateWatcher(backgroundScope, adapter)
        runCurrent()
        verify(exactly = 0) { adapter.updateTries() }
        domainCount.value = 120
        runCurrent()
        verify(exactly = 1) { adapter.updateTries() }
    }

    @Ignore("every VPN restart adds another domainCountFlow collector, so one change reloads the tries N times")
    @Test
    fun `restarting the watcher does not stack collectors`() = runTest {
        coordinator.startFilterUpdateWatcher(backgroundScope, adapter)
        coordinator.startFilterUpdateWatcher(backgroundScope, adapter)
        runCurrent()
        domainCount.value = 120
        runCurrent()
        verify(exactly = 1) { adapter.updateTries() }
    }
}
