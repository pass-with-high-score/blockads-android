package app.pwhs.blockads.ui.tailscale

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.datastore.prefs.TailscalePreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.service.VpnState
import app.pwhs.blockads.service.tailscale.ITailscaleService
import app.pwhs.blockads.testutil.ServiceStateHack
import app.pwhs.blockads.ui.MainDispatcherRule
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TailscaleViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val appPrefs: AppPreferences = mockk(relaxed = true)
    private val tailscalePrefs: TailscalePreferences = mockk(relaxed = true)

    @Before
    fun setUp() {
        ServiceStateHack.reset()
        stopKoin()
        every { appPrefs.tailscale } returns tailscalePrefs
        every { tailscalePrefs.pinnedPeerIds } returns emptyFlow()
        coEvery { tailscalePrefs.isEnabledSnapshot() } returns false
        coEvery { tailscalePrefs.getControlUrlSnapshot() } returns ""
        coEvery { tailscalePrefs.getAuthKeySnapshot() } returns ""
        coEvery { tailscalePrefs.getExitNodeIdSnapshot() } returns 0L
        coEvery { tailscalePrefs.getAdvertiseExitNodeSnapshot() } returns false
        coEvery { tailscalePrefs.getAdblockEnabledSnapshot() } returns true
        coEvery { tailscalePrefs.getStateJsonSnapshot() } returns ""
        coEvery { tailscalePrefs.getPinnedPeerIdsSnapshot() } returns emptySet()
        coEvery { tailscalePrefs.getTaildropDirUriSnapshot() } returns ""
        coEvery { tailscalePrefs.getTaildropDirNameSnapshot() } returns "Downloads/Taildrop"

        startKoin {
            modules(module {
                single { appPrefs }
            })
        }
    }

    @After
    fun tearDown() {
        ServiceStateHack.reset()
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `initial state when disabled and vpn stopped reflects DISCONNECTED`() = runTest {
        coEvery { tailscalePrefs.isEnabledSnapshot() } returns false
        AdBlockVpnService.status.state.value = VpnState.STOPPED

        val vm = TailscaleViewModel(app)
        advanceUntilIdle()

        assertEquals("DISCONNECTED", vm.uiState.value.connectionState)
        assertFalse(vm.uiState.value.isEnabled)
    }

    @Test
    fun `initial state when enabled and vpn stopped reflects VPN_DISCONNECTED`() = runTest {
        coEvery { tailscalePrefs.isEnabledSnapshot() } returns true
        AdBlockVpnService.status.state.value = VpnState.STOPPED

        val vm = TailscaleViewModel(app)
        advanceUntilIdle()

        assertEquals("VPN_DISCONNECTED", vm.uiState.value.connectionState)
        assertTrue(vm.uiState.value.isEnabled)
    }

    @Test
    fun `selectExitNode updates state without restarting VPN`() = runTest {
        mockkObject(ServiceController)
        val vm = TailscaleViewModel(app)
        advanceUntilIdle()

        vm.processIntent(TailscaleUiIntent.SelectExitNode(12345L))
        advanceUntilIdle()

        assertEquals(12345L, vm.uiState.value.activeExitNodeId)
        verify(exactly = 0) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `binding occurs when vpn is running`() = runTest {
        AdBlockVpnService.status.state.value = VpnState.RUNNING

        val vm = TailscaleViewModel(app)
        advanceUntilIdle()

        val shadowApp = shadowOf(app)
        assertTrue("AdBlockVpnService should be bound", shadowApp.boundServiceConnections.isNotEmpty())
    }

    @Test
    fun `unbinding occurs when vpn transitions from running to stopped`() = runTest {
        AdBlockVpnService.status.state.value = VpnState.RUNNING
        val vm = TailscaleViewModel(app)
        advanceUntilIdle()

        val shadowApp = shadowOf(app)
        assertTrue(shadowApp.boundServiceConnections.isNotEmpty())

        AdBlockVpnService.status.state.value = VpnState.STOPPED
        advanceUntilIdle()

        assertTrue(shadowApp.boundServiceConnections.isEmpty())
        assertEquals("DISCONNECTED", vm.uiState.value.connectionState)
    }

    @Test
    fun `logout preserves controlUrl and taildrop dir while resetting session`() = runTest {
        coEvery { tailscalePrefs.getControlUrlSnapshot() } returns "https://custom.control.com"
        coEvery { tailscalePrefs.getTaildropDirNameSnapshot() } returns "CustomTaildrop"
        coEvery { tailscalePrefs.getTaildropDirUriSnapshot() } returns "content://custom/uri"

        val vm = TailscaleViewModel(app)
        advanceUntilIdle()

        vm.processIntent(TailscaleUiIntent.Logout)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isEnabled)
        assertFalse(vm.uiState.value.isLoggedIn)
        assertEquals("DISCONNECTED", vm.uiState.value.connectionState)
        assertEquals("https://custom.control.com", vm.uiState.value.controlUrl)
        assertEquals("CustomTaildrop", vm.uiState.value.taildropDirName)
        assertEquals("content://custom/uri", vm.uiState.value.taildropDirUri)
    }

    @Test
    fun `peer ping reports peer unreachable when vpn is running without bound service`() = runTest {
        AdBlockVpnService.status.state.value = VpnState.RUNNING
        val client = mockk<TailscaleServiceClient>(relaxed = true)
        coEvery { client.pingPeer("100.64.0.5") } returns ""

        val vm = TailscaleViewModel(app, client)
        advanceUntilIdle()

        val initialPeer = TailscalePeer(
            id = 100L, name = "peer-a", ips = listOf("100.64.0.5"),
            ipv4 = "100.64.0.5", ipv6 = null, os = "linux", online = true,
            isExitNode = false, isPinned = false, isSelf = false,
            keyExpiry = 0L, keyExpired = false, curAddr = "", relay = ""
        )
        vm.processIntent(TailscaleUiIntent.SelectPeerForDetail(initialPeer))
        vm.processIntent(TailscaleUiIntent.PingPeer(100L, "100.64.0.5"))
        advanceUntilIdle()

        val updatedPeer = vm.uiState.value.selectedPeerForDetail
        assertEquals("Peer unreachable", updatedPeer?.pingError)
        assertFalse(updatedPeer!!.isPingLoading)
    }

    @Test
    fun `TailscaleServiceClient preserves isBound across onServiceDisconnected`() {
        val client = TailscaleServiceClient(app)
        client.bind()
        assertTrue(client.isBound)

        val stub = object : ITailscaleService.Stub() {
            override fun getTailscaleStatus(): String = """{"state":"CONNECTED"}"""
            override fun configureTailscale(stateJson: String?, controlUrl: String?, authKey: String?): String = "{}"
            override fun startTailscaleSession(hostname: String?) {}
            override fun stopTailscale() {}
            override fun setTailscaleExitNode(nodeId: Long) {}
            override fun setTailscaleAdvertiseExitNode(enabled: Boolean) {}
            override fun setTailscaleAdBlockEnabled(enabled: Boolean) {}
            override fun sendTailscaleFile(target: String?, fileName: String?, filePath: String?): String = ""
            override fun pingTailscalePeer(target: String?): String = ""
        }

        val shadowApp = shadowOf(app)
        val conn = shadowApp.boundServiceConnections.first()
        conn.onServiceConnected(null, stub)
        assertTrue("isConnected must be true after onServiceConnected with valid binder", client.isConnected)

        conn.onServiceDisconnected(null)

        // Requirement R4: onServiceDisconnected must NOT clear isBound
        assertTrue("isBound must remain true on service disconnection", client.isBound)
        assertFalse("isConnected must be false after service disconnection", client.isConnected)

        client.unbind()
        assertFalse("isBound is cleared only on explicit unbind", client.isBound)
    }

    @Test
    fun `logout cancels ongoing sync and keeps state disabled`() = runTest {
        coEvery { tailscalePrefs.isEnabledSnapshot() } returns true
        coEvery { tailscalePrefs.getStateJsonSnapshot() } returns """{"node_key":"k1","machine_key":"k2"}"""
        AdBlockVpnService.status.state.value = VpnState.RUNNING

        var syncWasCancelled = false
        val client = mockk<TailscaleServiceClient>(relaxed = true)
        every { client.isConnected } returns true
        coEvery { client.getStatus() } returns ""
        coEvery { client.syncSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            try {
                kotlinx.coroutines.delay(60_000L)
                """{"node_key":"stale","machine_key":"stale"}"""
            } catch (e: kotlinx.coroutines.CancellationException) {
                syncWasCancelled = true
                throw e
            }
        }

        val vm = TailscaleViewModel(app, client)
        testScheduler.runCurrent()

        vm.processIntent(TailscaleUiIntent.Logout)
        advanceUntilIdle()

        assertTrue("Active sync coroutine must be cancelled on logout", syncWasCancelled)
        assertFalse(vm.uiState.value.isEnabled)
        assertFalse(vm.uiState.value.isLoggedIn)
        assertEquals("DISCONNECTED", vm.uiState.value.connectionState)
        io.mockk.coVerify(exactly = 0) { tailscalePrefs.setStateJson("""{"node_key":"stale","machine_key":"stale"}""") }
    }

    @Test
    fun `toggleAdvertiseExitNode and toggleAdBlock delegate to serviceClient`() = runTest {
        val client = mockk<TailscaleServiceClient>(relaxed = true)
        coEvery { client.setAdvertiseExitNode(true) } returns true
        coEvery { client.setAdBlockEnabled(false) } returns true

        val vm = TailscaleViewModel(app, client)
        advanceUntilIdle()

        vm.processIntent(TailscaleUiIntent.ToggleAdvertiseExitNode(true))
        vm.processIntent(TailscaleUiIntent.ToggleAdBlock(false))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.advertiseExitNode)
        assertFalse(vm.uiState.value.adblockEnabled)
        io.mockk.coVerify { client.setAdvertiseExitNode(true) }
        io.mockk.coVerify { client.setAdBlockEnabled(false) }
    }
}
