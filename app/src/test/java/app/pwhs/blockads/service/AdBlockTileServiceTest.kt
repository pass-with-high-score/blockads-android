package app.pwhs.blockads.service

import android.app.Application
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.testutil.ServiceStateHack
import app.pwhs.blockads.utils.VpnUtils
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AdBlockTileServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var routingMode = AppPreferences.ROUTING_MODE_DIRECT
    private val prefs: AppPreferences = mockk { coEvery { getRoutingModeSnapshot() } answers { this@AdBlockTileServiceTest.routingMode } }
    private lateinit var tile: Tile

    init {
        startKoin { modules(module { single { prefs } }) }
    }

    @After
    fun tearDown() {
        ServiceStateHack.reset()
        stopKoin()
        unmockkAll()
    }

    private fun service(): AdBlockTileService {
        val service = Robolectric.buildService(AdBlockTileService::class.java).create().get()
        tile = service.qsTile
        return service
    }

    private fun started(): Intent? = shadowOf(app).nextStartedService

    @Test
    fun `listening reflects stopped, VPN and root states`() {
        service().onStartListening()
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        assertEquals("Disabled", tile.subtitle)

        ServiceStateHack.setVpn(VpnState.RUNNING)
        service().onStartListening()
        assertEquals(Tile.STATE_ACTIVE, tile.state)
        assertEquals("Protected", tile.subtitle)

        ServiceStateHack.setVpn(VpnState.STOPPED)
        ServiceStateHack.setRoot(VpnState.RUNNING)
        service().onStartListening()
        assertEquals("Root Proxy", tile.subtitle)
    }

    @Test
    fun `tap while stopped starts the VPN and flips the tile optimistically`() {
        service().onClick()
        assertEquals(AdBlockVpnService.ACTION_START, started()!!.action)
        assertEquals(Tile.STATE_ACTIVE, tile.state)
    }

    @Test
    fun `tap while stopped in root mode starts the root proxy`() {
        routingMode = AppPreferences.ROUTING_MODE_ROOT
        service().onClick()
        assertEquals(RootProxyService.ACTION_START, started()!!.action)
    }

    @Test
    fun `tap while running stops whichever service is up`() {
        ServiceStateHack.setVpn(VpnState.RUNNING)
        service().onClick()
        assertEquals(AdBlockVpnService.ACTION_STOP, started()!!.action)

        ServiceStateHack.setVpn(VpnState.STOPPED)
        ServiceStateHack.setRoot(VpnState.RUNNING)
        service().onClick()
        assertEquals(RootProxyService.ACTION_STOP, started()!!.action)
    }

    @Test
    fun `taps while stopping are ignored`() {
        ServiceStateHack.setVpn(VpnState.STOPPING)
        service().onClick()
        assertNull(started())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `another active VPN opens the conflict dialog instead of starting`() {
        mockkObject(VpnUtils)
        every { VpnUtils.isOtherVpnActive(any()) } returns true
        service().onClick()
        assertNull(started())
        val opened = shadowOf(app).nextStartedActivity
        assertTrue(opened.getBooleanExtra(MainActivity.EXTRA_SHOW_VPN_CONFLICT_DIALOG, false))
    }

    @Ignore("Suspected: a tap during STARTING sends another START instead of cancelling")
    @Test
    fun `tap while starting does not start again`() {
        ServiceStateHack.setVpn(VpnState.STARTING)
        service().onClick()
        assertTrue(started()?.action != AdBlockVpnService.ACTION_START)
    }
}
