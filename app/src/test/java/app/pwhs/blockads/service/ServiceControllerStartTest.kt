package app.pwhs.blockads.service

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.LinkProperties
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.testutil.ServiceStateHack
import app.pwhs.blockads.testutil.awaitTrue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class ServiceControllerStartTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = ServiceStateHack.reset()

    private fun routing(mode: String) = runBlocking { AppPreferences(app).setRoutingMode(mode) }

    private fun awaitStarted(): Intent {
        var started: Intent? = null
        awaitTrue(message = "service start") { shadowOf(app).nextStartedService?.also { started = it } != null }
        return started!!
    }

    @Test
    fun `start dispatches on the routing mode`() {
        routing(AppPreferences.ROUTING_MODE_DIRECT)
        ServiceController.requestStart(app)
        assertEquals(AdBlockVpnService.ACTION_START, awaitStarted().action)

        routing(AppPreferences.ROUTING_MODE_ROOT)
        ServiceController.requestStart(app)
        assertEquals(RootProxyService.ACTION_START, awaitStarted().action)

        routing(AppPreferences.ROUTING_MODE_WIREGUARD)
        ServiceController.requestStart(app)
        assertEquals(AdBlockVpnService.ACTION_START, awaitStarted().action)
    }

    @Test
    fun `restart only reaches a running service`() {
        ServiceController.requestRestart(app)
        assertNull(shadowOf(app).nextStartedService)

        ServiceStateHack.setVpn(VpnState.RUNNING)
        ServiceController.requestRestart(app)
        assertEquals(AdBlockVpnService.ACTION_RESTART, shadowOf(app).nextStartedService.action)

        ServiceStateHack.reset()
        ServiceStateHack.setRoot(VpnState.RUNNING)
        ServiceController.requestRestart(app)
        assertEquals(RootProxyService.ACTION_RESTART, shadowOf(app).nextStartedService.action)
    }

    @Test
    fun `private DNS strict flag follows the link properties`() {
        AdBlockVpnService.updatePrivateDnsState(mockk<LinkProperties> { every { privateDnsServerName } returns "dns.test" })
        assertTrue(AdBlockVpnService.privateDnsStrict.value)
        AdBlockVpnService.updatePrivateDnsState(null)
        assertFalse(AdBlockVpnService.privateDnsStrict.value)
    }

    @Ignore("a background startService throws IllegalStateException inside a bare IO scope with no handler")
    @Test
    fun `a refused background start is handled instead of crashing the thread`() {
        routing(AppPreferences.ROUTING_MODE_DIRECT)
        val refusing = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startService(service: Intent): ComponentName =
                throw IllegalStateException("Not allowed to start service: app is in background")
        }
        val crashes = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
        try {
            ServiceController.requestStart(refusing)
            Thread.sleep(500)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertTrue(crashes.toString(), crashes.isEmpty())
    }
}
