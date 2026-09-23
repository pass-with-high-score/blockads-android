package app.pwhs.blockads.service

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.testutil.FakeRootShell
import app.pwhs.blockads.testutil.FakeRootShell.FakeResult
import app.pwhs.blockads.testutil.ServiceStateHack
import app.pwhs.blockads.testutil.ShadowGoSeq
import app.pwhs.blockads.testutil.awaitTrue
import app.pwhs.blockads.utils.LibsuRootShell
import com.topjohnwu.superuser.Shell
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
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

/** RootProxyService end to end over [FakeRootShell] and a mocked Go engine. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowGoSeq::class], instrumentedPackages = ["go", "tunnel"])
class RootProxyServiceShellTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engine: Engine = mockk(relaxed = true)
    private val shell = FakeRootShell(respond = { cmd ->
        if (cmd.startsWith("iptables -t nat -L OUTPUT")) FakeResult(out = listOf("BLOCKADS_DNS")) else FakeResult()
    })
    private var whitelist = emptySet<String>()
    private val prefs: AppPreferences = mockk(relaxed = true) {
        every { recordDnsLogs } returns emptyFlow()
        every { dnsProtocol } returns flowOf(DnsProtocol.PLAIN)
        every { upstreamDns } returns flowOf("1.1.1.1")
        every { fallbackDns } returns flowOf("9.9.9.9")
        every { dohUrl } returns flowOf("")
        every { safeSearchEnabled } returns flowOf(false)
        every { youtubeRestrictedMode } returns flowOf(false)
        every { dnsResponseType } returns flowOf("NXDOMAIN")
        every { firewallEnabled } returns flowOf(false)
        coEvery { getWhitelistedAppsSnapshot() } answers { whitelist }
    }
    private val filterRepo: FilterListRepository = mockk(relaxed = true) {
        coEvery { loadAllEnabledFilters() } returns Result.success(10)
    }
    private lateinit var controller: ServiceController<RootProxyService>

    @Before
    fun setUp() {
        startKoin {
            modules(module {
                single { prefs }
                single { filterRepo }
                single<DnsLogDao> { mockk(relaxed = true) }
                single<FirewallRuleDao> { mockk(relaxed = true) }
            })
        }
        mockkStatic(Tunnel::class)
        every { Tunnel.newEngine() } returns engine
        // AppNameResolver's /proc/net snapshotter still calls libsu directly.
        mockkStatic(Shell::class)
        every { Shell.cmd(*anyVararg()) } returns mockk { every { exec() } returns mockk(relaxed = true) }
        IptablesManager.shell = shell
        controller = Robolectric.buildService(RootProxyService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
        IptablesManager.shell = LibsuRootShell
        ServiceStateHack.reset()
        stopKoin()
        unmockkAll()
    }

    private fun command(action: String) {
        controller.withIntent(Intent(app, RootProxyService::class.java).setAction(action)).startCommand(0, 1)
    }

    private val uid get() = app.applicationInfo.uid

    private fun startRunning() {
        command(RootProxyService.ACTION_START)
        // The service flips to RUNNING a moment before it records the start time, on another thread.
        awaitTrue(message = "RUNNING") { RootProxyService.isRunning && RootProxyService.startTimestamp > 0 }
    }

    @Test
    fun `start brings up the engine on 15353 and installs the redirect`() {
        startRunning()
        verify { engine.startStandalone(15353L) }
        assertTrue(shell.commands.containsAll(IptablesManager.buildIpv4Commands(uid, true, emptyList())))
        assertTrue(RootProxyService.startTimestamp > 0)
    }

    @Test
    fun `whitelisted apps are exempted by uid and unknown packages skipped`() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.bank"
            applicationInfo = ApplicationInfo().apply { packageName = "com.bank"; uid = 10444 }
        })
        whitelist = setOf("com.bank", "not.installed")
        startRunning()
        assertTrue(shell.commands.containsAll(IptablesManager.buildIpv4Commands(uid, true, listOf(10444))))
    }

    @Test
    fun `stop tears the rules down and stops the engine`() {
        startRunning()
        command(RootProxyService.ACTION_STOP)
        assertEquals(VpnState.STOPPED, RootProxyService.state.value)
        assertEquals(IptablesManager.teardownCommands(), shell.batches.last())
        verify { engine.stop() }
        assertEquals(0L, RootProxyService.startTimestamp)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `restart keeps the uptime`() {
        startRunning()
        val startedAt = RootProxyService.startTimestamp
        command(RootProxyService.ACTION_RESTART)
        assertEquals(VpnState.RESTARTING, RootProxyService.state.value)
        awaitTrue(message = "RUNNING again") { RootProxyService.isRunning }
        assertEquals(startedAt, RootProxyService.startTimestamp)
    }

    @Test
    fun `a startup exception tears the rules down and stops the service`() {
        every { prefs.dnsProtocol } throws IllegalStateException("prefs")
        command(RootProxyService.ACTION_START)
        awaitTrue(message = "stopSelf") { shadowOf(controller.get()).isStoppedBySelf }
        assertEquals(IptablesManager.teardownCommands(), shell.batches.last())
    }

    @Ignore("known bug: stop during STARTING does not cancel the start job, which then ends RUNNING")
    @Test
    fun `stop during STARTING ends STOPPED`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { filterRepo.loadWhitelist() } coAnswers { gate.await() }
        command(RootProxyService.ACTION_START)
        command(RootProxyService.ACTION_STOP)
        gate.complete(Unit)
        runCatching { awaitTrue(timeoutMs = 3_000) { RootProxyService.isRunning } }
        assertEquals(VpnState.STOPPED, RootProxyService.state.value)
    }

    @Ignore("known bug: onTaskRemoved removes the iptables rules but the state stays RUNNING")
    @Test
    fun `task removal does not leave a RUNNING state without rules`() {
        startRunning()
        controller.get().onTaskRemoved(null)
        assertEquals(IptablesManager.teardownCommands(), shell.batches.last())
        assertTrue(RootProxyService.state.value != VpnState.RUNNING)
    }
}
