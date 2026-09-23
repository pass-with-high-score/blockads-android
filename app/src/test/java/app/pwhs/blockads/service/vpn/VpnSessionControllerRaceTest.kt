package app.pwhs.blockads.service.vpn

import app.pwhs.blockads.service.VpnState
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Overlapping lifecycle calls: stop racing start, superseded shutdowns, revoke and destroy. */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnSessionControllerRaceTest {

    private fun session(block: suspend TestScope.(VpnSessionFixture) -> Unit) = runTest {
        block(VpnSessionFixture(this))
    }

    private fun TestScope.startRunning(f: VpnSessionFixture) {
        f.controller.start()
        advanceUntilIdle()
        assertEquals(VpnState.RUNNING, f.state)
        f.events.clear()
    }

    @Ignore("known bug: stop during STARTING does not cancel the start job, which then ends RUNNING")
    @Test
    fun `stop during STARTING ends STOPPED`() = session { f ->
        f.configGate = CompletableDeferred()
        f.controller.start()
        runCurrent()
        f.controller.stop()
        advanceUntilIdle()
        f.configGate!!.complete(Unit)
        advanceUntilIdle()
        f.finalizeStops()
        assertEquals(VpnState.STOPPED, f.state)
    }

    @Ignore("known bug: stop during the retry backoff lets the next attempt bring the session back to RUNNING")
    @Test
    fun `stop during retry backoff ends STOPPED`() = session { f ->
        f.tunnelResults += listOf(TunnelResult.Failure, f.success())
        f.controller.start()
        runCurrent()
        assertEquals(1, f.establishCalls)
        f.controller.stop()
        advanceUntilIdle()
        f.finalizeStops()
        assertEquals(VpnState.STOPPED, f.state)
    }

    @Ignore("known bug: stop during restart's cleanup delay is overridden when the delayed start runs")
    @Test
    fun `stop during a restart's cleanup delay ends STOPPED`() = session { f ->
        startRunning(f)
        f.controller.restart()
        advanceTimeBy(500)
        f.controller.stop()
        advanceUntilIdle()
        f.finalizeStops()
        assertEquals(VpnState.STOPPED, f.state)
    }

    @Test
    fun `a stop superseded by a new start leaves the new session alone`() = session { f ->
        startRunning(f)
        f.controller.stop()
        f.controller.start()
        advanceUntilIdle()
        f.finalizeStops()

        assertEquals(VpnState.RUNNING, f.state)
        assertFalse("stopSelf" in f.events)
        assertFalse("fullyStopped" in f.events)
        assertEquals(listOf(true, false, true), f.vpnEnabledWrites)
    }

    @Ignore("known bug: a superseded stop still runs engine.stop() after the new session started its tunnel")
    @Test
    fun `a superseded stop does not stop the new session's engine`() = session { f ->
        startRunning(f)
        f.controller.stop()
        f.controller.start()
        advanceUntilIdle()
        val lastEngineStop = f.events.lastIndexOf("engineStop")
        val newTunnel = f.events.indexOfFirst { it.startsWith("startTunnel") }
        assertTrue("engine stopped at $lastEngineStop after the new tunnel at $newTunnel", lastEngineStop < newTunnel)
    }

    @Test
    fun `a revoke within 10s of establishing is ignored as stale`() = session { f ->
        startRunning(f)
        f.elapsed += 9_999
        assertFalse(f.controller.onRevoke())
        advanceUntilIdle()
        assertEquals(VpnState.RUNNING, f.state)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `a later revoke stops quietly with the revoked notice`() = session { f ->
        startRunning(f)
        f.elapsed += 10_000
        assertTrue(f.controller.onRevoke())
        assertEquals(VpnState.STOPPING, f.state)
        advanceUntilIdle()
        assertEquals("revoked-notice", f.events.first())
        assertTrue("stopForeground(remove=true)" in f.events)
        assertFalse("stopped-notice" in f.events)
        assertEquals(listOf(true, false, false), f.vpnEnabledWrites)
        f.finalizeStops()
        assertEquals(VpnState.STOPPED, f.state)
    }

    @Ignore("known bug: a genuine revoke (another VPN taking over) within 10s of establishing is swallowed")
    @Test
    fun `a genuine revoke right after establishing still stops the session`() = session { f ->
        startRunning(f)
        f.elapsed += 2_000
        f.controller.onRevoke()
        advanceUntilIdle()
        assertTrue(f.state != VpnState.RUNNING)
    }

    @Test
    fun `destroy while RUNNING marks STOPPED, closes the tunnel and cancels pending work`() = session { f ->
        startRunning(f)
        val tun = f.tunnels.single()
        f.controller.onDestroy()
        assertEquals(VpnState.STOPPED, f.state)
        assertTrue(f.status.lastStoppedTimestamp > 0)
        assertEquals(0L, f.status.startTimestamp)
        verify { tun.close() }
        assertEquals(listOf("netWatch-", "periodic-"), f.events)

        f.controller.start()
        advanceUntilIdle()
        assertEquals(VpnState.STARTING, f.state)
        assertEquals(1, f.establishCalls)
    }

    @Test
    fun `destroy while STOPPING leaves the state for the stop finalizer`() = session { f ->
        startRunning(f)
        f.controller.stop()
        f.controller.onDestroy()
        assertEquals(VpnState.STOPPING, f.state)
        assertEquals(0L, f.status.lastStoppedTimestamp)
    }
}
