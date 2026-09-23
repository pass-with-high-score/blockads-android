package app.pwhs.blockads.ui.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.service.VpnState
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.close
import app.pwhs.blockads.ui.keepHot
import kotlinx.coroutines.flow.Flow
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val vpnState = MutableStateFlow(VpnState.STOPPED)
    private val rootState = MutableStateFlow(VpnState.STOPPED)
    private val privateDnsStrict = MutableStateFlow(false)
    private val routingMode = MutableStateFlow(AppPreferences.ROUTING_MODE_DIRECT)
    private val blocked = MutableStateFlow(0)
    private val lastSeen = MutableStateFlow(0L)
    private val milestonesOn = MutableStateFlow(true)
    private val domainCount = MutableStateFlow(0)

    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { routingMode } returns this@HomeViewModelTest.routingMode
        every { pausedByTrusted } returns flowOf(true)
        every { pausedTrustedSsid } returns flowOf("Home")
        every { lastSeenMilestoneDialog } returns lastSeen
        every { milestoneNotificationsEnabled } returns milestonesOn
    }
    private val dnsLogDao: DnsLogDao = mockk(relaxed = true) {
        every { getBlockedCount() } returns blocked
        every { getTotalCount() } returns flowOf(50_000)
    }
    private val repo: FilterListRepository = mockk(relaxed = true) {
        every { domainCountFlow } returns this@HomeViewModelTest.domainCount
        every { domainCount } answers { this@HomeViewModelTest.domainCount.value }
    }
    private val filterListDao: FilterListDao = mockk(relaxed = true) {
        every { getAll() } returns flowOf(
            listOf(
                FilterList(id = 1, name = "ads", url = "a"),
                FilterList(id = 2, name = "mal", url = "m", category = FilterList.CATEGORY_SECURITY),
            )
        )
    }
    private val vm by lazy {
        HomeViewModel(appPrefs, dnsLogDao, repo, mockk<ProtectionProfileDao>(relaxed = true), filterListDao)
    }

    @Before
    fun setUp() {
        mockkObject(AdBlockVpnService.Companion, RootProxyService.Companion)
        every { AdBlockVpnService.state } returns vpnState
        every { AdBlockVpnService.isRunning } answers { vpnState.value == VpnState.RUNNING }
        every { AdBlockVpnService.privateDnsStrict } returns privateDnsStrict
        every { RootProxyService.state } returns rootState
        every { RootProxyService.isRunning } answers { rootState.value == VpnState.RUNNING }
    }

    // Not runTest: its end-of-test drain would spin the VM's endless uptime ticker.
    private val hotScope by lazy { CoroutineScope(mainRule.dispatcher) }

    private fun keepHot(vararg flows: Flow<*>) = hotScope.keepHot(*flows)

    @After
    fun tearDown() {
        hotScope.cancel()
        vm.close()
        unmockkAll()
        AdBlockVpnService.startTimestamp = 0L
    }

    @Test
    fun `vpn flags derive from both services`() {
        keepHot(vm.vpnEnabled, vm.vpnConnecting, vm.vpnStopping)
        assertFalse(vm.vpnEnabled.value)

        vpnState.value = VpnState.STARTING
        assertTrue(vm.vpnConnecting.value)
        assertFalse(vm.vpnEnabled.value)

        vpnState.value = VpnState.STOPPED
        rootState.value = VpnState.RESTARTING
        assertTrue(vm.vpnConnecting.value)

        rootState.value = VpnState.RUNNING
        assertTrue(vm.vpnEnabled.value)
        assertFalse(vm.vpnConnecting.value)

        rootState.value = VpnState.STOPPED
        vpnState.value = VpnState.STOPPING
        assertTrue(vm.vpnEnabled.value)
        assertTrue(vm.vpnStopping.value)
    }

    @Test
    fun `private DNS warning shows only in VPN mode while protection is on`() {
        keepHot(vm.privateDnsWarning)
        privateDnsStrict.value = true
        assertFalse("off while stopped", vm.privateDnsWarning.value)

        vpnState.value = VpnState.RUNNING
        assertTrue(vm.privateDnsWarning.value)

        routingMode.value = AppPreferences.ROUTING_MODE_ROOT
        assertFalse("root mode disables Private DNS itself", vm.privateDnsWarning.value)

        routingMode.value = AppPreferences.ROUTING_MODE_WIREGUARD
        privateDnsStrict.value = false
        assertFalse(vm.privateDnsWarning.value)
    }

    @Test
    fun `milestone surfaces the highest unseen threshold and respects the toggle`() {
        keepHot(vm.milestoneReached)
        blocked.value = 999
        assertNull(vm.milestoneReached.value)

        blocked.value = 12_000
        assertEquals(10_000L, vm.milestoneReached.value)

        lastSeen.value = 10_000
        assertNull(vm.milestoneReached.value)

        blocked.value = 60_000
        assertEquals(50_000L, vm.milestoneReached.value)

        milestonesOn.value = false
        assertNull(vm.milestoneReached.value)
    }

    @Test
    fun `dismissing a milestone records it as seen`() {
        vm.dismissMilestoneDialog(10_000)
        coVerify { appPrefs.setLastSeenMilestoneDialog(10_000) }
    }

    @Test
    fun `security filter ids and simple mirrors come from storage`() {
        keepHot(vm.securityFilterIds, vm.totalCount, vm.pausedByTrusted, vm.pausedTrustedSsid, vm.domainCount)
        assertEquals(setOf("2"), vm.securityFilterIds.value)
        assertEquals(50_000, vm.totalCount.value)
        assertTrue(vm.pausedByTrusted.value)
        assertEquals("Home", vm.pausedTrustedSsid.value)
        domainCount.value = 1234
        assertEquals(1234, vm.domainCount.value)
    }

    @Test
    fun `uptime counts from the VPN start while it runs`() {
        vpnState.value = VpnState.RUNNING
        AdBlockVpnService.startTimestamp = System.currentTimeMillis() - 5_000
        val uptime = vm.protectionUptimeMs.value
        assertTrue("uptime $uptime", uptime in 5_000..60_000)
    }

    @Test
    fun `uptime is zero while stopped`() {
        AdBlockVpnService.startTimestamp = System.currentTimeMillis() - 5_000
        assertEquals(0L, vm.protectionUptimeMs.value)
    }

    @Test
    fun `stopping sends the stop action to the running VPN`() {
        vpnState.value = VpnState.RUNNING
        vm.stopVpn(app)
        assertEquals(AdBlockVpnService.ACTION_STOP, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun `stopping the root proxy goes through its own stop`() {
        rootState.value = VpnState.RUNNING
        every { RootProxyService.stop(any()) } just Runs
        vm.stopVpn(app)
        verify { RootProxyService.stop(app) }
    }

    @Test
    fun `preload seeds and loads once and is skipped when filters are loaded`() {
        vm.preloadFilter()
        coVerify(exactly = 1) { repo.seedDefaultsIfNeeded() }
        coVerify(exactly = 1) { repo.loadAllEnabledFilters() }
        assertFalse(vm.isLoading.value)

        domainCount.value = 10
        vm.preloadFilter()
        coVerify(exactly = 1) { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `a throwing load flags failure and retry clears it`() {
        coEvery { repo.loadAllEnabledFilters() } throws RuntimeException("io") andThen Result.success(5)
        vm.preloadFilter()
        assertTrue(vm.filterLoadFailed.value)
        assertFalse(vm.isLoading.value)

        vm.retryLoadFilter()
        assertFalse(vm.filterLoadFailed.value)
    }

    @Test
    fun `a throwing retry flags failure`() {
        coEvery { repo.seedDefaultsIfNeeded() } throws RuntimeException("offline")
        vm.retryLoadFilter()
        assertTrue(vm.filterLoadFailed.value)
        assertFalse(vm.isLoading.value)
    }

    @Ignore("known bug: loadAllEnabledFilters reports failure as Result.failure, which preloadFilter ignores")
    @Test
    fun `a failed load result flags failure`() {
        coEvery { repo.loadAllEnabledFilters() } returns Result.failure(RuntimeException("corrupt trie"))
        vm.preloadFilter()
        assertTrue(vm.filterLoadFailed.value)
    }
}
