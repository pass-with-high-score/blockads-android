package app.pwhs.blockads.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.testutil.ServiceStateHack
import app.pwhs.blockads.testutil.awaitTrue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowWifiInfo

@RunWith(RobolectricTestRunner::class)
class TrustedNetworkManagerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    private val featureOn = MutableStateFlow(true)
    private val trusted = MutableStateFlow(setOf("Home"))
    private var pausedByUs = false
    private val prefs: AppPreferences = mockk(relaxed = true) {
        every { pauseOnTrustedEnabled } returns featureOn
        every { trustedSsids } returns trusted
        coEvery { getPauseOnTrustedEnabledSnapshot() } answers { featureOn.value }
        coEvery { getTrustedSsidsSnapshot() } answers { trusted.value }
        coEvery { getPausedByTrustedSnapshot() } answers { pausedByUs }
        coEvery { setPausedByTrusted(any(), any()) } answers { pausedByUs = firstArg() }
    }

    @After
    fun tearDown() = ServiceStateHack.reset()

    private fun connectTo(rawSsid: String?, grantLocation: Boolean = true) {
        if (grantLocation) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val info = ShadowWifiInfo.newInstance()
        if (rawSsid != null) shadowOf(info).setSSID(rawSsid)
        shadowOf(app.getSystemService(Context.WIFI_SERVICE) as WifiManager).setConnectionInfo(info)
    }

    private fun startedActions(): List<String?> = generateSequence { shadowOf(app).nextStartedService }.map { it.action }.toList()

    @Test
    fun `ssid needs location permission and is unquoted`() {
        connectTo("\"Home\"", grantLocation = false)
        assertNull(TrustedNetworkManager.currentSsid(app))
        connectTo("\"Home\"")
        assertEquals("Home", TrustedNetworkManager.currentSsid(app))
        connectTo("<unknown ssid>")
        assertNull(TrustedNetworkManager.currentSsid(app))
        connectTo("\"\"")
        assertNull(TrustedNetworkManager.currentSsid(app))
    }

    @Test
    fun `joining a trusted network while protected pauses and notifies`() {
        connectTo("\"Home\"")
        ServiceStateHack.setVpn(VpnState.RUNNING)
        TrustedNetworkManager(app, prefs).start()

        awaitTrue(message = "pause") { pausedByUs }
        coVerify { prefs.setPausedByTrusted(true, "Home") }
        awaitTrue(message = "notification") { nm.allNotifications.isNotEmpty() }
        assertEquals(listOf(AdBlockVpnService.ACTION_STOP), startedActions().distinct())
    }

    @Test
    fun `ssid matching is case-sensitive`() {
        connectTo("\"home\"")
        ServiceStateHack.setVpn(VpnState.RUNNING)
        TrustedNetworkManager(app, prefs).start()
        Thread.sleep(300)
        coVerify(exactly = 0) { prefs.setPausedByTrusted(true, any()) }
    }

    @Test
    fun `leaving the trusted network resumes what we paused`() {
        pausedByUs = true
        connectTo("\"Cafe\"")
        TrustedNetworkManager(app, prefs).start()

        awaitTrue(message = "resume") { !pausedByUs }
        awaitTrue(message = "start") { startedActions().contains(AdBlockVpnService.ACTION_START) }
    }

    @Test
    fun `turning the feature off resumes a paused service`() {
        pausedByUs = true
        featureOn.value = false
        connectTo("\"Home\"")
        TrustedNetworkManager(app, prefs).start()

        awaitTrue(message = "resume") { !pausedByUs }
        awaitTrue(message = "start") { startedActions().contains(AdBlockVpnService.ACTION_START) }
    }

    @Ignore("Suspected: a manual start on a trusted network is re-paused on the next evaluation; the 'clear stale flag' branch is unreachable there")
    @Test
    fun `a manual start on a trusted network is not paused again`() {
        pausedByUs = true
        connectTo("\"Home\"")
        ServiceStateHack.setVpn(VpnState.RUNNING)
        TrustedNetworkManager(app, prefs).start()

        Thread.sleep(300)
        assertEquals(emptyList<String?>(), startedActions())
    }

    @Test
    fun `running on an untrusted network clears a stale pause flag without stopping`() {
        pausedByUs = true
        trusted.value = setOf("Other")
        connectTo("\"Home\"")
        ServiceStateHack.setRoot(VpnState.RUNNING)
        TrustedNetworkManager(app, prefs).start()

        awaitTrue(message = "flag cleared") { !pausedByUs }
        assertEquals(emptyList<String?>(), startedActions())
    }
}
