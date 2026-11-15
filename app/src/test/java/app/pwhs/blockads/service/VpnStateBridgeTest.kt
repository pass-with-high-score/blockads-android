package app.pwhs.blockads.service

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.LinkProperties
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.DirectBootPreferences
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
        shadowOf(context as Application).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
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

    @Test
    fun testVpnStateBridgePrefNameIsIsolatedFromDirectBootPreferences() {
        assertEquals("blockads_vpn_state_bridge", VpnStateBridge.PREF_NAME)

        val directBootPrefs = DirectBootPreferences(context)
        directBootPrefs.wasVpnEnabled = true

        VpnStateBridge.persistState(
            context = context,
            state = VpnState.STOPPED,
            startTimestamp = 0L,
            lastStoppedTimestamp = 1000L,
            privateDnsStrict = false
        )

        // Ensure DirectBootPreferences wasVpnEnabled is not overwritten by VpnStateBridge persistState
        assertTrue("DirectBootPreferences wasVpnEnabled must remain untouched", directBootPrefs.wasVpnEnabled)
    }

    @Test
    fun testInitMainProcessBridgeRegistersReceiverAndHydratesState() {
        VpnStateBridge.resetBridgeForTesting()
        val store = VpnStatusStore()

        // Persist initial state
        VpnStateBridge.persistState(
            context = context,
            state = VpnState.STOPPED,
            startTimestamp = 0L,
            lastStoppedTimestamp = 2000L,
            privateDnsStrict = false
        )

        VpnStateBridge.initMainProcessBridge(context, store)
        assertEquals(2000L, store.lastStoppedTimestamp)

        // Send a state change broadcast to verify the registered receiver updates the store
        val intent = Intent(AdBlockVpnService.ACTION_VPN_STATE_CHANGED).apply {
            putExtra(AdBlockVpnService.EXTRA_VPN_STATE, VpnState.RUNNING.name)
            putExtra(AdBlockVpnService.EXTRA_VPN_PHASE, "Loading filters...")
            putExtra(AdBlockVpnService.EXTRA_START_TIMESTAMP, 9999L)
            putExtra(AdBlockVpnService.EXTRA_LAST_STOPPED_TIMESTAMP, 2000L)
            putExtra(AdBlockVpnService.EXTRA_PRIVATE_DNS_STRICT, true)
        }
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VpnState.RUNNING, store.state.value)
        assertEquals("Loading filters...", store.phase.value)
        assertEquals(9999L, store.startTimestamp)
        assertTrue(store.privateDnsStrict.value)
    }

    @Test
    fun testAdBlockVpnServiceDelegatesInitMainProcessBridge() {
        VpnStateBridge.resetBridgeForTesting()
        AdBlockVpnService.status.state.value = VpnState.STOPPED

        AdBlockVpnService.initMainProcessBridge(context)

        val intent = Intent(AdBlockVpnService.ACTION_VPN_STATE_CHANGED).apply {
            putExtra(AdBlockVpnService.EXTRA_VPN_STATE, VpnState.RUNNING.name)
            putExtra(AdBlockVpnService.EXTRA_START_TIMESTAMP, 8888L)
            putExtra(AdBlockVpnService.EXTRA_LAST_STOPPED_TIMESTAMP, 0L)
            putExtra(AdBlockVpnService.EXTRA_PRIVATE_DNS_STRICT, false)
        }
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VpnState.RUNNING, AdBlockVpnService.status.state.value)
        assertEquals(8888L, AdBlockVpnService.status.startTimestamp)
    }

    @Test
    fun testLivenessMonitorResetsStateOnServiceDisconnected() {
        VpnStateBridge.resetBridgeForTesting()
        val store = VpnStatusStore()
        VpnStateBridge.initMainProcessBridge(context, store)

        val intent = Intent(AdBlockVpnService.ACTION_VPN_STATE_CHANGED).apply {
            putExtra(AdBlockVpnService.EXTRA_VPN_STATE, VpnState.RUNNING.name)
            putExtra(AdBlockVpnService.EXTRA_START_TIMESTAMP, 7777L)
            putExtra(AdBlockVpnService.EXTRA_LAST_STOPPED_TIMESTAMP, 0L)
            putExtra(AdBlockVpnService.EXTRA_PRIVATE_DNS_STRICT, false)
        }
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VpnState.RUNNING, store.state.value)
        assertTrue(VpnStateBridge.isLivenessBound)

        val shadowApp = shadowOf(context as Application)
        val conn = shadowApp.boundServiceConnections.last()
        conn.onServiceDisconnected(null)

        assertEquals(VpnState.STOPPED, store.state.value)
        assertEquals(0L, store.startTimestamp)
        assertFalse(VpnStateBridge.isLivenessBound)
    }
}
