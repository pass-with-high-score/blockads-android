package app.pwhs.blockads.service.vpn

import app.pwhs.blockads.R
import app.pwhs.blockads.service.VpnState
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Start, stop, retry and revoke paths of [VpnSessionController] on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnSessionControllerTest {

    private fun session(block: suspend TestScope.(VpnSessionFixture) -> Unit) = runTest {
        block(VpnSessionFixture(this))
    }

    private fun TestScope.startRunning(f: VpnSessionFixture) {
        f.controller.start()
        advanceUntilIdle()
        assertEquals(VpnState.RUNNING, f.state)
        f.events.clear()
    }

    @Test
    fun `start goes STARTING then RUNNING and hands the tunnel to the engine`() = session { f ->
        f.tunnelResults += f.success("{wg}")
        f.controller.start()
        assertEquals(VpnState.STARTING, f.state)
        assertEquals(listOf("foreground", "netWatch+"), f.events)

        advanceUntilIdle()
        assertEquals(listOf(VpnState.STOPPED, VpnState.STARTING, VpnState.RUNNING), f.states)
        assertEquals(listOf(true), f.vpnEnabledWrites)
        assertEquals(1, f.establishCalls)
        assertEquals("", f.controller.connectingPhase)
        assertEquals(f.controller.vpnStartTime, f.status.startTimestamp)
        assertTrue(f.status.startTimestamp > 0)
        val tail = f.events.dropWhile { it != "privateDns" }
        assertEquals(
            listOf("privateDns", "notify", "widget", "battery", "periodic+", "configure", "watcher", "startTunnel(0,{wg})"),
            tail,
        )
    }

    @Test
    fun `start walks the connecting phases in order`() = session { f ->
        f.controller.start()
        advanceUntilIdle()
        val expected = listOf(
            R.string.vpn_phase_loading_filters, R.string.vpn_phase_preparing_dns, R.string.vpn_phase_establishing,
        ).map { "phase#$it" } + ""
        assertEquals(expected, f.phases.distinct())
    }

    @Test
    fun `start is ignored while RUNNING or STARTING`() = session { f ->
        f.configGate = CompletableDeferred()
        f.controller.start()
        f.controller.start()
        runCurrent()
        assertEquals(1, f.events.count { it == "prepare" })
        f.configGate!!.complete(Unit)
        advanceUntilIdle()
        f.controller.start()
        advanceUntilIdle()
        assertEquals(1, f.establishCalls)
    }

    @Test
    fun `failure is retried five times on the backoff curve, then the session stops`() = session { f ->
        f.tunnelResults += TunnelResult.Failure
        f.controller.start()
        advanceUntilIdle()
        assertEquals(5, f.establishCalls)
        assertEquals(1_000L + 1_000 + 2_000 + 3_000 + 5_000, currentTime)
        assertEquals(VpnState.STOPPING, f.state)
        assertTrue("stopped-notice" in f.events)
        f.finalizeStops()
        assertEquals(VpnState.STOPPED, f.state)
    }

    @Test
    fun `a later success resets the retry counter`() = session { f ->
        f.tunnelResults += listOf(TunnelResult.Failure, TunnelResult.Failure, f.success())
        f.controller.start()
        advanceUntilIdle()
        assertEquals(VpnState.RUNNING, f.state)
        assertEquals(3, f.establishCalls)
        assertEquals(0, f.controller.retryManager.getRetryCount())
    }

    @Test
    fun `a revoked permission fails fast without the stopped notification`() = session { f ->
        f.tunnelResults += TunnelResult.PermissionRevoked
        f.controller.start()
        advanceUntilIdle()
        assertEquals(1, f.establishCalls)
        assertEquals(0L, currentTime)
        assertTrue("revoked-notice" in f.events)
        assertTrue("stopForeground(remove=true)" in f.events)
        assertFalse("stopped-notice" in f.events)
        f.finalizeStops()
        assertEquals(VpnState.STOPPED, f.state)
    }

    @Test
    fun `a startup exception stops the session`() = session { f ->
        f.configError = IllegalStateException("filters")
        f.controller.start()
        advanceUntilIdle()
        assertEquals(0, f.establishCalls)
        assertEquals(VpnState.STOPPING, f.state)
        assertTrue("stopped-notice" in f.events)
    }

    @Test
    fun `boot start waits for a network before establishing`() = session { f ->
        f.networkAvailable = false
        f.controller.start(startedFromBoot = true)
        advanceUntilIdle()
        assertEquals(0, f.establishCalls)
        assertEquals("phase#${R.string.vpn_phase_waiting_network}", f.controller.connectingPhase)

        f.networkAvailableFlow.emit(Unit)
        advanceUntilIdle()
        assertEquals(VpnState.RUNNING, f.state)
    }

    @Test
    fun `a manual start does not wait for the network`() = session { f ->
        f.networkAvailable = false
        f.controller.start()
        advanceUntilIdle()
        assertEquals(VpnState.RUNNING, f.state)
    }

    @Ignore("known bug: a boot start with no network waits forever in STARTING; there is no timeout")
    @Test
    fun `boot start gives up waiting for a network eventually`() = session { f ->
        f.networkAvailable = false
        f.controller.start(startedFromBoot = true)
        advanceTimeBy(10 * 60_000L)
        assertTrue(f.state != VpnState.STARTING)
    }

    @Test
    fun `stop tears down in order and finalizes to STOPPED once the transport is gone`() = session { f ->
        startRunning(f)
        f.status.privateDnsStrict.value = true
        val tun = f.tunnels.single()
        f.controller.stop()
        assertEquals(VpnState.STOPPING, f.state)
        assertEquals(0L, f.status.startTimestamp)
        assertEquals(listOf("cancelSwitch", "notify", "netWatch-", "periodic-"), f.events)

        advanceUntilIdle()
        verify { tun.close() }
        assertEquals(listOf(true, false), f.vpnEnabledWrites)
        assertEquals(
            listOf("engineStop", "stopForeground(remove=false)", "stopped-notice", "stopSelf"),
            f.events.drop(4),
        )
        assertEquals(VpnState.STOPPING, f.state)

        f.finalizeStops()
        assertEquals(VpnState.STOPPED, f.state)
        assertTrue(f.status.lastStoppedTimestamp > 0)
        assertFalse(f.status.privateDnsStrict.value)
        assertEquals("fullyStopped", f.events.last())
    }

    @Test
    fun `restart tears down, waits a second and comes back up keeping the uptime`() = session { f ->
        startRunning(f)
        val startedAt = f.controller.vpnStartTime
        advanceTimeBy(60_000)
        f.controller.restart()
        runCurrent()
        assertEquals(VpnState.RESTARTING, f.state)
        assertTrue(f.controller.isReconnecting)
        assertEquals(listOf("netWatch-", "periodic-", "engineStop"), f.events)
        verify { f.tunnels.first().close() }

        advanceUntilIdle()
        assertEquals(VpnState.RUNNING, f.state)
        assertEquals(2, f.tunnels.size)
        assertEquals(startedAt, f.controller.vpnStartTime)
        assertEquals(startedAt, f.status.startTimestamp)
        assertFalse(f.controller.isReconnecting)
    }

    @Test
    fun `restart is ignored unless RUNNING or STARTING`() = session { f ->
        f.controller.restart()
        advanceUntilIdle()
        assertEquals(VpnState.STOPPED, f.state)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `a supervisor start resets the retry counter`() = session { f ->
        f.tunnelResults += listOf(TunnelResult.Failure, f.success())
        f.controller.start()
        advanceUntilIdle()
        f.controller.tearDownForRestart()
        f.controller.startFromSupervisor()
        assertEquals(0, f.controller.retryManager.getRetryCount())
        advanceUntilIdle()
        assertEquals(VpnState.RUNNING, f.state)
    }

    @Ignore("known bug: a network-switch reconnect resets uptime; startFromSupervisor clears isReconnecting before the start job reads it")
    @Test
    fun `a supervisor reconnect keeps the uptime`() = session { f ->
        startRunning(f)
        val startedAt = f.controller.vpnStartTime
        advanceTimeBy(60_000)
        f.controller.tearDownForRestart()
        f.controller.startFromSupervisor()
        advanceUntilIdle()
        assertEquals(startedAt, f.controller.vpnStartTime)
    }

    @Test
    fun `physical network loss only renotifies on change`() = session { f ->
        f.controller.onPhysicalNetworkLostChanged(true)
        f.controller.onPhysicalNetworkLostChanged(true)
        f.controller.onPhysicalNetworkLostChanged(false)
        assertEquals(listOf("notify", "notify"), f.events)
    }
}
