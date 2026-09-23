package app.pwhs.blockads.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.datastore.DirectBootPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun prefs(enabled: Boolean, autoReconnect: Boolean = true, mode: String = AppPreferences.ROUTING_MODE_DIRECT) =
        runBlocking {
            AppPreferences(app).apply {
                setVpnEnabled(enabled)
                setAutoReconnect(autoReconnect)
                setRoutingMode(mode)
            }
        }

    private fun deliver(action: String): Intent? {
        val receiver = BootReceiver()
        app.registerReceiver(receiver, android.content.IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        app.sendBroadcast(Intent(action).setPackage(app.packageName))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val deadline = System.currentTimeMillis() + 1_000
        var started: Intent? = null
        while (System.currentTimeMillis() < deadline && started == null) {
            started = shadowOf(app).nextStartedService
            if (started == null) Thread.sleep(20)
        }
        app.unregisterReceiver(receiver)
        return started
    }

    @Test
    fun `boot restarts the VPN when it was on`() {
        prefs(enabled = true)
        val started = deliver(Intent.ACTION_BOOT_COMPLETED)!!
        assertEquals(AdBlockVpnService::class.java.name, started.component!!.className)
        assertEquals(AdBlockVpnService.ACTION_START, started.action)
        assertTrue(started.getBooleanExtra(AdBlockVpnService.EXTRA_STARTED_FROM_BOOT, false))
    }

    @Test
    fun `app update in root mode restarts the root proxy`() {
        prefs(enabled = true, mode = AppPreferences.ROUTING_MODE_ROOT)
        val started = deliver(Intent.ACTION_MY_PACKAGE_REPLACED)!!
        assertEquals(RootProxyService::class.java.name, started.component!!.className)
        assertEquals(RootProxyService.ACTION_START, started.action)
        assertTrue(started.getBooleanExtra(RootProxyService.EXTRA_STARTED_FROM_BOOT, false))
    }

    @Test
    fun `nothing starts when the VPN was off or auto-reconnect is disabled`() {
        prefs(enabled = false)
        assertNull(deliver(Intent.ACTION_BOOT_COMPLETED))
        prefs(enabled = true, autoReconnect = false)
        assertNull(deliver(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `unrelated actions are ignored`() {
        prefs(enabled = true)
        assertNull(deliver(Intent.ACTION_SCREEN_ON))
    }

    @Test
    fun `locked boot reads device-protected prefs`() {
        prefs(enabled = false)
        val userManager = app.getSystemService(Context.USER_SERVICE) as UserManager
        shadowOf(userManager).setUserUnlocked(false)
        DirectBootPreferences(app).apply {
            wasVpnEnabled = true
            autoReconnect = true
            routingMode = AppPreferences.ROUTING_MODE_ROOT
        }

        val started = deliver(Intent.ACTION_LOCKED_BOOT_COMPLETED)!!
        assertEquals(RootProxyService::class.java.name, started.component!!.className)
    }
}
