package app.pwhs.blockads.service

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.LinkProperties
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.service.vpn.VpnStatusStore
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class VpnStateBridgeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        AdBlockVpnService.status.privateDnsStrict.value = false
        AdBlockVpnService.status.state.value = VpnState.STOPPED
        AdBlockVpnService.status.startTimestamp = 0L
    }

    @Test
    fun testPersistAndHydrateState() {
        val store = VpnStatusStore()
        VpnStateBridge.persistState(
            context = context,
            state = VpnState.STOPPED,
            startTimestamp = 0L,
            lastStoppedTimestamp = 5000L,
            privateDnsStrict = false
        )

        VpnStateBridge.hydrateState(context, store)

        assertEquals(VpnState.STOPPED, store.state.value)
        assertEquals(0L, store.startTimestamp)
        assertEquals(5000L, store.lastStoppedTimestamp)
        assertFalse(store.privateDnsStrict.value)
    }

    @Test
    fun testColdBootReconciliationWhenNoVpnTransport() {
        val store = VpnStatusStore()
        // Simulate stale DirectBoot preference written before phone rebooted
        VpnStateBridge.persistState(
            context = context,
            state = VpnState.RUNNING,
            startTimestamp = 123456L,
            lastStoppedTimestamp = 0L,
            privateDnsStrict = true
        )

        // Without active VPN transport, cold boot hydration must reconcile to STOPPED
        VpnStateBridge.hydrateState(context, store)

        assertEquals(VpnState.STOPPED, store.state.value)
        assertEquals(0L, store.startTimestamp)
    }

    @Test
    fun testUpdatePrivateDnsStateBroadcastsWithContext() {
        AdBlockVpnService.status.privateDnsStrict.value = false

        val linkProperties = mockk<LinkProperties> {
            every { privateDnsServerName } returns "dns.example.com"
        }

        AdBlockVpnService.updatePrivateDnsState(linkProperties, context)

        assertTrue(AdBlockVpnService.status.privateDnsStrict.value)

        val broadcast = shadowOf(context as Application).broadcastIntents.find {
            it.action == AdBlockVpnService.ACTION_VPN_STATE_CHANGED
        }
        assertEquals(true, broadcast?.getBooleanExtra(AdBlockVpnService.EXTRA_PRIVATE_DNS_STRICT, false))
    }
}
