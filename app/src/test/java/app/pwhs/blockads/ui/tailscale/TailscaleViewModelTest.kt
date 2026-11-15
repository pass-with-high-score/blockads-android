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
import kotlinx.coroutines.launch
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

        conn.onBindingDied(null)
        assertTrue("isBound must remain true after onBindingDied rebind", client.isBound)

        client.unbind()
        assertFalse("isBound is cleared only on explicit unbind", client.isBound)
    }

    @Test
    fun `logout drains ongoing sync before calling stopTailscale and keeps state disabled`() = runTest {
        coEvery { tailscalePrefs.isEnabledSnapshot() } returns true
        coEvery { tailscalePrefs.getStateJsonSnapshot() } returns """{"node_key":"k1","machine_key":"k2"}"""
        AdBlockVpnService.status.state.value = VpnState.RUNNING

        val callOrder = mutableListOf<String>()
        val client = mockk<TailscaleServiceClient>(relaxed = true)
        every { client.isConnected } returns true
        coEvery { client.getStatus() } returns ""
        coEvery { client.syncSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            try {
                callOrder.add("sync_start")
                kotlinx.coroutines.delay(60_000L)
                """{"node_key":"stale","machine_key":"stale"}"""
            } finally {
                callOrder.add("sync_end")
            }
        }
        coEvery { client.stopTailscale() } coAnswers {
            callOrder.add("stop")
            true
        }

        val vm = TailscaleViewModel(app, client)
        testScheduler.runCurrent()

        vm.processIntent(TailscaleUiIntent.Logout)
        advanceUntilIdle()

        assertTrue("Sync must have started and ended", callOrder.contains("sync_end"))
        assertEquals("stop must be the final RPC after all syncs drain", "stop", callOrder.last())
        assertTrue(callOrder.lastIndexOf("sync_end") < callOrder.indexOf("stop"))
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

    @Test
    fun `disable teardown completes stopTailscale before unbind when ViewModel is cleared while join is pending`() = runTest {
        coEvery { tailscalePrefs.isEnabledSnapshot() } returns true
        coEvery { tailscalePrefs.getStateJsonSnapshot() } returns """{"node_key":"k1","machine_key":"k2"}"""
        AdBlockVpnService.status.state.value = VpnState.RUNNING

        val syncGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val callOrder = mutableListOf<String>()
        val client = mockk<TailscaleServiceClient>(relaxed = true)
        every { client.isConnected } returns true
        coEvery { client.getStatus() } returns ""
        coEvery { client.syncSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            callOrder.add("sync_start")
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                syncGate.await()
            }
            callOrder.add("sync_end")
            "{}"
        }
        coEvery { client.stopTailscale() } coAnswers {
            callOrder.add("stop")
            true
        }
        every { client.unbind() } answers {
            callOrder.add("unbind")
        }

        val viewModelStore = androidx.lifecycle.ViewModelStore()
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return TailscaleViewModel(app, client) as T
            }
        }
        val vm = androidx.lifecycle.ViewModelProvider(viewModelStore, factory)[TailscaleViewModel::class.java]
        testScheduler.runCurrent()

        vm.processIntent(TailscaleUiIntent.ToggleEnabled(false))
        testScheduler.runCurrent()

        // Clear the ViewModelStore while cancelAndJoin() is suspended on the non-cancellable RPC gate
        viewModelStore.clear()
        assertFalse("unbind must be deferred while disable teardown is active", callOrder.contains("unbind"))

        syncGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(callOrder.contains("stop"))
        assertTrue(callOrder.contains("unbind"))
        assertTrue("stop must complete before unbind", callOrder.indexOf("stop") < callOrder.indexOf("unbind"))
    }

    @Test
    fun `sendFileToPeer handles SecurityException from resolveFileName with failure snackbar`() = runTest {
        val client = mockk<TailscaleServiceClient>(relaxed = true)
        every { client.isConnected } returns true
        every { client.resolveFileName(any()) } throws SecurityException("URI permission revoked")

        val vm = TailscaleViewModel(app, client)
        advanceUntilIdle()

        val effectDeferred = kotlinx.coroutines.CompletableDeferred<TailscaleUiEffect>()
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            vm.uiEffect.collect { effectDeferred.complete(it) }
        }

        val peer = TailscalePeer(
            id = 100L, name = "peer-a", ips = listOf("100.64.0.5"),
            ipv4 = "100.64.0.5", ipv6 = null, os = "linux", online = true,
            isExitNode = false, isPinned = false, isSelf = false,
            keyExpiry = 0L, keyExpired = false, curAddr = "", relay = ""
        )
        vm.processIntent(TailscaleUiIntent.SendFileToPeer(peer, android.net.Uri.parse("content://test/file")))
        val effect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(2_000L) { effectDeferred.await() }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(2_000L) {
                while (vm.uiState.value.isSendingFile) {
                    kotlinx.coroutines.delay(10L)
                }
            }
        }

        assertFalse(vm.uiState.value.isSendingFile)
        assertTrue("Must emit failure snackbar on SecurityException", effect is TailscaleUiEffect.ShowSnackbar)
    }

    @Test
    fun `logout invalidates in-flight startAuthFlow while loading preferences`() = runTest {
        val prefGate = kotlinx.coroutines.CompletableDeferred<String>()
        var firstControlCall = true
        coEvery { tailscalePrefs.getControlUrlSnapshot() } coAnswers {
            if (firstControlCall) {
                firstControlCall = false
                ""
            } else {
                prefGate.await()
            }
        }

        val vm = TailscaleViewModel(app)
        advanceUntilIdle()

        vm.processIntent(TailscaleUiIntent.SignInWithBrowser)
        testScheduler.runCurrent()

        vm.processIntent(TailscaleUiIntent.Logout)
        prefGate.complete("https://control.example.com")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isEnabled)
        assertFalse(vm.uiState.value.isLoggedIn)
        assertEquals("DISCONNECTED", vm.uiState.value.connectionState)
    }

    @Test
    fun `overlapping disable and enable transitions serialize without stopping newer session`() = runTest {
        coEvery { tailscalePrefs.isEnabledSnapshot() } returns true
        coEvery { tailscalePrefs.getStateJsonSnapshot() } returns """{"node_key":"k1","machine_key":"k2"}"""
        AdBlockVpnService.status.state.value = VpnState.RUNNING

        val syncGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var firstSync = true
        val callOrder = mutableListOf<String>()
        val client = mockk<TailscaleServiceClient>(relaxed = true)
        every { client.isConnected } returns true
        coEvery { client.getStatus() } returns ""
        coEvery { client.syncSession(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (firstSync) {
                firstSync = false
                callOrder.add("sync1_start")
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    syncGate.await()
                }
                callOrder.add("sync1_end")
            } else {
                callOrder.add("sync_subsequent")
            }
            "{}"
        }
        coEvery { client.stopTailscale() } coAnswers {
            callOrder.add("stop")
            true
        }

        val vm = TailscaleViewModel(app, client)
        testScheduler.runCurrent()

        // Disable then immediately re-enable while sync1 is still finishing
        vm.processIntent(TailscaleUiIntent.ToggleEnabled(false))
        vm.processIntent(TailscaleUiIntent.ToggleEnabled(true))
        testScheduler.runCurrent()

        syncGate.complete(Unit)
        advanceUntilIdle()

        assertTrue("stop must precede the re-enable sync", callOrder.indexOf("stop") < callOrder.lastIndexOf("sync_subsequent"))
        assertTrue(vm.uiState.value.isEnabled)
        assertTrue(vm.uiState.value.connectionState != "DISCONNECTED")
    }
}
