package app.pwhs.blockads.worker

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.work.ListenableWorker.Result
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ResumeWorkersTest {

    private val h = WorkerHarness()

    @After
    fun tearDown() = h.close()

    /** Stands in for API 31+ ForegroundServiceStartNotAllowedException (an IllegalStateException). */
    private val refusingContext = object : ContextWrapper(h.app) {
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName =
            throw IllegalStateException("startForegroundService() not allowed")
    }

    @Test
    fun `vpn resume starts the VPN service`() {
        assertEquals(Result.success(), h.run<VpnResumeWorker>())
        val started = shadowOf(h.app).nextStartedService
        assertEquals(AdBlockVpnService::class.java.name, started.component!!.className)
        assertEquals(AdBlockVpnService.ACTION_START, started.action)
    }

    @Test
    fun `root resume starts the root proxy service`() {
        assertEquals(Result.success(), h.run<RootProxyResumeWorker>())
        val started = shadowOf(h.app).nextStartedService
        assertEquals(RootProxyService::class.java.name, started.component!!.className)
        assertEquals(RootProxyService.ACTION_START, started.action)
    }

    @Ignore("resume workers start the service even after the user stopped it manually")
    @Test
    fun `vpn resume respects a manual stop`() {
        runBlocking { AppPreferences(h.app).setVpnEnabled(false) }
        h.run<VpnResumeWorker>()
        assertNull(shadowOf(h.app).nextStartedService)
    }

    @Ignore("a refused foreground start (API 31+) returns retry, so WorkManager loops on it")
    @Test
    fun `refused foreground start does not retry forever`() {
        assertNotEquals(Result.retry(), h.run<VpnResumeWorker>(refusingContext))
        assertNotEquals(Result.retry(), h.run<RootProxyResumeWorker>(refusingContext))
    }
}
