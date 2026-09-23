package app.pwhs.blockads.service

import android.content.Context
import android.os.PowerManager
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.vpn.VpnConnectionSupervisor
import app.pwhs.blockads.utils.BatteryMonitor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnConnectionSupervisorTest {

    private var running = false
    private var idle = true
    private var autoReconnect = true
    private var vpnWasEnabled = true
    private var delayEnabled = false
    private var delaySec = 3
    private var interactive = true

    private val phases = mutableListOf<String>()
    private val physicalLost = mutableListOf<Boolean>()
    private var teardowns = 0
    private var starts = 0
    private var notificationUpdates = 0
    private var statsRefreshes = 0
    private var restarts = 0

    private val powerManager: PowerManager = mockk { every { isInteractive } answers { interactive } }
    private val context: Context = mockk {
        every { getString(any(), *anyVararg()) } answers { "waiting" }
        every { getSystemService(Context.POWER_SERVICE) } returns powerManager
    }
    private val prefs: AppPreferences = mockk {
        every { autoReconnect } answers { flowOf(this@VpnConnectionSupervisorTest.autoReconnect) }
        every { vpnEnabled } answers { flowOf(vpnWasEnabled) }
        every { networkSwitchDelayEnabled } answers { flowOf(delayEnabled) }
        every { networkSwitchDelaySec } answers { flowOf(delaySec) }
    }
    private val battery: BatteryMonitor = mockk(relaxed = true)

    @After
    fun tearDown() = unmockkAll()

    /** advanceUntilIdle ignores backgroundScope work, so step virtual time past any countdown instead. */
    private fun TestScope.settle() = advanceTimeBy(30_000)

    private fun TestScope.supervisor() = VpnConnectionSupervisor(
        context = context,
        scope = backgroundScope,
        appPrefs = prefs,
        batteryMonitor = battery,
        isRunningProvider = { running },
        isIdleProvider = { idle },
        socketProtector = { true },
        onTearDownForRestart = { teardowns++; running = false; idle = true },
        onStartVpn = { starts++; running = true; idle = false },
        onPhaseChanged = { phases += it },
        onRefreshStats = { statsRefreshes++ },
        onUpdateNotification = { notificationUpdates++ },
        onLinkPropertiesChanged = {},
        onPhysicalNetworkLostChanged = { physicalLost += it },
        onNetworkActiveChanged = {},
        onRequestRestart = { restarts++ },
    )

    @Test
    fun `idle and previously enabled reconnects after the stabilization delay`() = runTest {
        val s = supervisor()
        s.onNetworkAvailable()
        advanceTimeBy(1_999)
        assertEquals(0, starts)
        advanceTimeBy(2)
        assertEquals(1, starts)
        assertTrue(phases.isEmpty())
    }

    @Test
    fun `no reconnect when auto-reconnect is off, VPN was not enabled, or service is busy`() = runTest {
        val s = supervisor()
        autoReconnect = false
        s.onNetworkAvailable(); settle()
        autoReconnect = true; vpnWasEnabled = false
        s.onNetworkAvailable(); settle()
        vpnWasEnabled = true; idle = false
        s.onNetworkAvailable(); settle()
        assertEquals(0, starts)
    }

    @Test
    fun `no reconnect if the service left idle during the delay`() = runTest {
        val s = supervisor()
        s.onNetworkAvailable()
        advanceTimeBy(1_000)
        idle = false
        settle()
        assertEquals(0, starts)
    }

    @Test
    fun `network switch while running tears down, counts down and restarts`() = runTest {
        delayEnabled = true
        running = true; idle = false
        val s = supervisor()

        s.onNetworkAvailable()
        runCurrent()
        assertEquals(1, teardowns)
        advanceTimeBy(2_500)
        assertEquals(0, starts)
        settle()

        assertEquals(1, starts)
        assertEquals(listOf("waiting", "waiting", "waiting", ""), phases)
        assertEquals(3, notificationUpdates)
    }

    @Test
    fun `auto reconnect with the delay enabled counts down before starting`() = runTest {
        delayEnabled = true; delaySec = 2
        val s = supervisor()
        s.onNetworkAvailable()
        settle()
        assertEquals(1, starts)
        assertEquals(0, teardowns)
        assertEquals(listOf("waiting", "waiting", ""), phases)
    }

    @Test
    fun `stop during the countdown cancels the restart`() = runTest {
        delayEnabled = true
        running = true; idle = false
        val s = supervisor()
        s.onNetworkAvailable()
        advanceTimeBy(1_500)

        s.cancelNetworkSwitch()
        settle()
        assertEquals(1, teardowns)
        assertEquals(0, starts)
    }

    @Test
    fun `flapping networks end in a single start`() = runTest {
        delayEnabled = true
        running = true; idle = false
        val s = supervisor()

        s.onNetworkAvailable()
        advanceTimeBy(500)
        s.onNetworkAvailable()
        advanceTimeBy(1_000)
        s.onNetworkAvailable()
        settle()

        assertEquals(1, teardowns)
        assertEquals(1, starts)
    }

    @Test
    fun `network availability is broadcast`() = runTest {
        val s = supervisor()
        val got = backgroundScope.launch { s.networkAvailableFlow.first() }
        runCurrent()
        s.onNetworkAvailable()
        runCurrent()
        assertTrue(got.isCompleted)
        assertTrue(s.isNetworkAvailable())
    }

    private fun stubProbe(vararg statuses: ConnectionStatus) {
        mockkConstructor(ConnectionQualityProbe::class)
        coEvery { anyConstructed<ConnectionQualityProbe>().runDiagnosis() } returnsMany statuses.map {
            ProbeResult(it, physicalOk = it != ConnectionStatus.NO_PHYSICAL_INTERNET, vpnDnsOk = it == ConnectionStatus.HEALTHY, latencyMs = 1)
        }
    }

    @Test
    fun `two consecutive stalls request a restart`() = runTest {
        stubProbe(ConnectionStatus.VPN_TUNNEL_STALLED, ConnectionStatus.VPN_TUNNEL_STALLED, ConnectionStatus.VPN_TUNNEL_STALLED)
        running = true
        val s = supervisor()
        s.startPeriodicMonitoring()

        advanceTimeBy(60_001)
        assertEquals(0, restarts)
        advanceTimeBy(60_000)
        assertEquals(1, restarts)
        advanceTimeBy(60_000)
        assertEquals("the counter resets after a restart", 1, restarts)
        s.stopPeriodicMonitoring()
    }

    @Test
    fun `a healthy probe resets the stall counter`() = runTest {
        stubProbe(ConnectionStatus.VPN_TUNNEL_STALLED, ConnectionStatus.HEALTHY, ConnectionStatus.VPN_TUNNEL_STALLED)
        running = true
        val s = supervisor()
        s.startPeriodicMonitoring()
        advanceTimeBy(180_001)
        s.stopPeriodicMonitoring()

        assertEquals(0, restarts)
        assertEquals(listOf(false, false, false), physicalLost)
    }

    @Test
    fun `no physical internet is reported and does not count as a stall`() = runTest {
        stubProbe(ConnectionStatus.VPN_TUNNEL_STALLED, ConnectionStatus.NO_PHYSICAL_INTERNET, ConnectionStatus.VPN_TUNNEL_STALLED)
        running = true
        val s = supervisor()
        s.startPeriodicMonitoring()
        advanceTimeBy(180_001)
        s.stopPeriodicMonitoring()

        assertEquals(0, restarts)
        assertEquals(listOf(false, true, false), physicalLost)
    }

    @Test
    fun `periodic monitoring refreshes stats, updates the notification when interactive and logs battery`() = runTest {
        stubProbe(*Array(10) { ConnectionStatus.HEALTHY })
        running = true
        val s = supervisor()
        s.startPeriodicMonitoring()

        advanceTimeBy(60_001)
        assertEquals(3, statsRefreshes)
        assertEquals(2, notificationUpdates)

        interactive = false
        advanceTimeBy(60_000)
        assertEquals(2, notificationUpdates)

        advanceTimeBy(180_000)
        verify(exactly = 1) { battery.logBatteryStatus() }

        s.stopPeriodicMonitoring()
        val refreshesAtStop = statsRefreshes
        advanceTimeBy(600_000)
        assertEquals(refreshesAtStop, statsRefreshes)
    }

    @Test
    fun `monitoring loops end when the service stops running`() = runTest {
        stubProbe(*Array(10) { ConnectionStatus.VPN_TUNNEL_STALLED })
        running = true
        val s = supervisor()
        s.startPeriodicMonitoring()
        advanceTimeBy(30_001)
        running = false
        advanceTimeBy(600_000)

        assertEquals(0, restarts)
        assertFalse(physicalLost.isNotEmpty())
    }
}
