package app.pwhs.blockads.receiver

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TaskerReceiverTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var appPrefs: AppPreferences

    @Before
    fun setUp() {
        appPrefs = AppPreferences(app)
        AdBlockVpnService.status.state.value = VpnState.STOPPED
    }

    @After
    fun tearDown() {
        AdBlockVpnService.status.state.value = VpnState.STOPPED
    }

    @Test
    fun `TASKER_START requests start of VPN service`() = runBlocking {
        val receiver = TaskerReceiver()
        val intent = Intent(TaskerReceiver.ACTION_START)

        receiver.onReceive(app, intent)
        delay(200)

        val started = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_START, started?.action)
    }

    @Test
    fun `TASKER_STOP requests stop of VPN service`() = runBlocking {
        AdBlockVpnService.status.state.value = VpnState.RUNNING

        val receiver = TaskerReceiver()
        val intent = Intent(TaskerReceiver.ACTION_STOP)

        receiver.onReceive(app, intent)
        delay(200)

        val started = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_STOP, started?.action)
    }

    @Test
    fun `TASKER_START_PROXY sets proxy_vpn mode and restarts running VPN`() = runBlocking {
        AdBlockVpnService.status.state.value = VpnState.RUNNING
        appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)

        val receiver = TaskerReceiver()
        val intent = Intent(TaskerReceiver.ACTION_START_PROXY)

        receiver.onReceive(app, intent)
        delay(200)

        assertEquals(AppPreferences.ROUTING_MODE_PROXY_VPN, appPrefs.routingMode.first())
        val started = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_RESTART, started?.action)
    }

    @Test
    fun `TASKER_START_PROXY with profile selects profile and activates proxy`() = runBlocking {
        AdBlockVpnService.status.state.value = VpnState.RUNNING
        val proxyProfile = ProxyProfile(
            id = "test-proxy-id",
            name = "Tokyo Node",
            server = "tokyo.proxy.com",
            port = 443
        )
        appPrefs.addProxyProfile(proxyProfile)

        val receiver = TaskerReceiver()
        val intent = Intent(TaskerReceiver.ACTION_START_PROXY).apply {
            putExtra(TaskerReceiver.EXTRA_PROFILE, "Tokyo Node")
        }

        receiver.onReceive(app, intent)
        delay(200)

        assertEquals(AppPreferences.ROUTING_MODE_PROXY_VPN, appPrefs.routingMode.first())
        assertEquals("test-proxy-id", appPrefs.activeProxyProfileId.first())
        assertEquals(AppPreferences.PROXY_TYPE_SHADOWSOCKS, appPrefs.activeProxyType.first())
        val started = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_RESTART, started?.action)
    }

    @Test
    fun `TASKER_STOP_PROXY sets direct mode and restarts running VPN without stopping it`() = runBlocking {
        AdBlockVpnService.status.state.value = VpnState.RUNNING
        appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_PROXY_VPN)

        val receiver = TaskerReceiver()
        val intent = Intent(TaskerReceiver.ACTION_STOP_PROXY)

        receiver.onReceive(app, intent)
        delay(200)

        assertEquals(AppPreferences.ROUTING_MODE_DIRECT, appPrefs.routingMode.first())
        val started = shadowOf(app).nextStartedService
        assertEquals(AdBlockVpnService.ACTION_RESTART, started?.action)
    }

    @Test
    fun `TASKER_TOGGLE_PROXY toggles from proxy to direct`() = runBlocking {
        AdBlockVpnService.status.state.value = VpnState.RUNNING
        appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_PROXY_VPN)

        val receiver = TaskerReceiver()
        val intent = Intent(TaskerReceiver.ACTION_TOGGLE_PROXY)

        receiver.onReceive(app, intent)
        delay(200)

        assertEquals(AppPreferences.ROUTING_MODE_DIRECT, appPrefs.routingMode.first())
    }

    @Test
    fun `TASKER_TOGGLE_PROXY toggles from direct to proxy`() = runBlocking {
        AdBlockVpnService.status.state.value = VpnState.RUNNING
        appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)

        val receiver = TaskerReceiver()
        val intent = Intent(TaskerReceiver.ACTION_TOGGLE_PROXY)

        receiver.onReceive(app, intent)
        delay(200)

        assertEquals(AppPreferences.ROUTING_MODE_PROXY_VPN, appPrefs.routingMode.first())
    }
}
