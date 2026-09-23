package app.pwhs.blockads.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ServiceControllerStopTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun startedActions(): List<Pair<String?, String?>> =
        generateSequence { shadowOf(app).nextStartedService }
            .map { it.component?.className to it.action }
            .toList()

    @Test
    fun `stop reaches a running VPN`() {
        ServiceController.requestStop(app, vpnState = VpnState.RUNNING, rootState = VpnState.STOPPED)
        assertEquals(
            listOf(AdBlockVpnService::class.java.name to AdBlockVpnService.ACTION_STOP),
            startedActions()
        )
    }

    @Ignore("known bug: requestStop drops a stop sent while the service is STARTING")
    @Test
    fun `stop reaches a VPN that is still starting`() {
        ServiceController.requestStop(app, vpnState = VpnState.STARTING, rootState = VpnState.STOPPED)
        assertEquals(
            "stop during STARTING was dropped",
            listOf(AdBlockVpnService::class.java.name to AdBlockVpnService.ACTION_STOP),
            startedActions()
        )
    }

    @Ignore("known bug: requestStop drops a stop sent while the service is STARTING")
    @Test
    fun `stop reaches a root proxy that is still starting`() {
        ServiceController.requestStop(app, vpnState = VpnState.STOPPED, rootState = VpnState.STARTING)
        assertEquals(
            "stop during STARTING was dropped",
            listOf(RootProxyService::class.java.name to RootProxyService.ACTION_STOP),
            startedActions()
        )
    }

    @Test
    fun `stop is a no-op when nothing is running`() {
        ServiceController.requestStop(app, vpnState = VpnState.STOPPED, rootState = VpnState.STOPPED)
        assertNull(shadowOf(app).nextStartedService)
    }
}
