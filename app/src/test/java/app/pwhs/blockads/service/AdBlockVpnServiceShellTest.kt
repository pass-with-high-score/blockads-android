package app.pwhs.blockads.service

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.vpn.VpnNotificationManager
import app.pwhs.blockads.testutil.ServiceStateHack
import app.pwhs.blockads.testutil.ShadowGoSeq
import app.pwhs.blockads.testutil.awaitTrue
import app.pwhs.blockads.worker.VpnResumeWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import tunnel.Engine
import tunnel.Tunnel

/** Wiring of the AdBlockVpnService shell to VpnSessionController, with the Go engine and VpnService.Builder mocked. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowGoSeq::class], instrumentedPackages = ["go", "tunnel"])
class AdBlockVpnServiceShellTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engine: Engine = mockk(relaxed = true)
    private val tun: ParcelFileDescriptor = mockk(relaxed = true)
    private val vpnEnabledWrites = mutableListOf<Boolean>()
    private val prefs: AppPreferences = mockk(relaxed = true) {
        every { recordDnsLogs } returns emptyFlow()
        every { blockDohBypass } returns emptyFlow()
        every { upstreamDns } returns flowOf("1.1.1.1")
        every { fallbackDns } returns flowOf("9.9.9.9")
        every { dnsResponseType } returns flowOf("NXDOMAIN")
        every { dnsProtocol } returns flowOf(DnsProtocol.PLAIN)
        every { dohUrl } returns flowOf("")
        every { safeSearchEnabled } returns flowOf(false)
        every { youtubeRestrictedMode } returns flowOf(false)
        every { firewallEnabled } returns flowOf(false)
        every { dnsProviderId } returns flowOf("cloudflare")
        every { splitDnsZones } returns flowOf("")
        every { excludeLan } returns flowOf(false)
        every { allowAppBypass } returns flowOf(false)
        every { lastMilestoneBlocked } returns flowOf(0L)
        every { getSelectedBrowsersSnapshot() } returns emptySet()
        coEvery { getWhitelistedAppsSnapshot() } returns emptySet()
        coEvery { getRoutingModeSnapshot() } returns AppPreferences.ROUTING_MODE_DIRECT
        coEvery { getHttpsFilteringEnabledSnapshot() } returns false
        coEvery { setVpnEnabled(any()) } answers { vpnEnabledWrites += firstArg<Boolean>() }
    }
    private val filterRepo: FilterListRepository = mockk(relaxed = true) {
        every { domainCountFlow } returns MutableStateFlow(10)
        coEvery { loadAllEnabledFilters() } returns Result.success(10)
    }
    private val dnsLogDao: DnsLogDao = mockk(relaxed = true)
    private lateinit var controller: ServiceController<AdBlockVpnService>

    @Before
    fun setUp() {
        startKoin {
            modules(module {
                single { prefs }
                single { filterRepo }
                single { dnsLogDao }
                single<FirewallRuleDao> { mockk(relaxed = true) }
            })
        }
        mockkStatic(Tunnel::class)
        every { Tunnel.newEngine() } returns engine
        mockkStatic(VpnService::class)
        every { VpnService.prepare(any()) } returns null
        mockkConstructor(VpnService.Builder::class)
        every { anyConstructed<VpnService.Builder>().setSession(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setMtu(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setBlocking(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addAddress(any<String>(), any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addRoute(any<String>(), any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addDnsServer(any<String>()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addDisallowedApplication(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().allowBypass() } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setUnderlyingNetworks(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setMetered(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().establish() } returns tun
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        controller = Robolectric.buildService(AdBlockVpnService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
        ServiceStateHack.reset()
        stopKoin()
        unmockkAll()
    }

    private fun command(action: String) {
        controller.withIntent(Intent(app, AdBlockVpnService::class.java).setAction(action)).startCommand(0, 1)
    }

    private val service get() = controller.get()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Test
    fun `start brings the session up and attaches the engine to the tunnel`() {
        command(AdBlockVpnService.ACTION_START)
        assertEquals(VpnState.STARTING, AdBlockVpnService.state.value)
        assertTrue(shadowOf(service).isForegroundStopped.not())

        awaitTrue(message = "RUNNING") { AdBlockVpnService.isRunning }
        awaitTrue(message = "engine start") { runCatching { verify { engine.startFull(any(), any()) } }.isSuccess }
        assertEquals(listOf(true), vpnEnabledWrites)
        assertTrue(AdBlockVpnService.startTimestamp > 0)
    }

    @Test
    fun `stop from RUNNING stops the engine, closes the tunnel and stops the service`() {
        command(AdBlockVpnService.ACTION_START)
        awaitTrue(message = "RUNNING") { AdBlockVpnService.isRunning }
        command(AdBlockVpnService.ACTION_STOP)
        assertEquals(VpnState.STOPPING, AdBlockVpnService.state.value)

        awaitTrue(message = "stopSelf") { shadowOf(service).isStoppedBySelf }
        verify { tun.close() }
        verify { engine.stop() }
        assertTrue(notifications.getNotification(VpnNotificationManager.NOTIFICATION_ID) != null)
        awaitTrue(message = "STOPPED") { AdBlockVpnService.state.value == VpnState.STOPPED }
        assertEquals(false, vpnEnabledWrites.last())
    }

    @Test
    fun `a revoked VPN permission stops the service with the revoked notification`() {
        every { VpnService.prepare(any()) } returns Intent()
        command(AdBlockVpnService.ACTION_START)
        awaitTrue(message = "stopSelf") { shadowOf(service).isStoppedBySelf }
        assertTrue(notifications.getNotification(VpnNotificationManager.REVOKED_NOTIFICATION_ID) != null)
    }

    @Test
    fun `pause schedules the resume worker and stops`() {
        command(AdBlockVpnService.ACTION_PAUSE_1H)
        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork(VpnResumeWorker.WORK_NAME).get()
        assertEquals(1, work.size)
        awaitTrue(message = "stopSelf") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun `restart from RUNNING comes back up on a fresh tunnel`() {
        command(AdBlockVpnService.ACTION_START)
        awaitTrue(message = "RUNNING") { AdBlockVpnService.isRunning }
        command(AdBlockVpnService.ACTION_RESTART)
        awaitTrue(message = "RESTARTING") { AdBlockVpnService.isRestarting }
        awaitTrue(message = "RUNNING again") { AdBlockVpnService.isRunning }
        verify(exactly = 2) { anyConstructed<VpnService.Builder>().establish() }
    }

    @Test
    fun `destroy marks the session STOPPED`() {
        command(AdBlockVpnService.ACTION_START)
        awaitTrue(message = "RUNNING") { AdBlockVpnService.isRunning }
        controller.destroy()
        assertEquals(VpnState.STOPPED, AdBlockVpnService.state.value)
        verify { tun.close() }
    }
}
