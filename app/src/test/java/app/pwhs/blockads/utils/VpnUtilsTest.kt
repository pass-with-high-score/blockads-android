package app.pwhs.blockads.utils

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.service.AdBlockVpnService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VpnUtilsTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        AdBlockVpnService.lastStoppedTimestamp = 0L
    }

    private fun network(vpn: Boolean?): Pair<Network, NetworkCapabilities?> {
        val caps = vpn?.let { v ->
            mockk<NetworkCapabilities> { every { hasTransport(any()) } answers { v && firstArg<Int>() == NetworkCapabilities.TRANSPORT_VPN } }
        }
        return mockk<Network>() to caps
    }

    private fun contextWith(active: Pair<Network, NetworkCapabilities?>?, vararg others: Pair<Network, NetworkCapabilities?>): Context {
        val all = others.toList()
        val cm = mockk<ConnectivityManager> {
            every { activeNetwork } returns active?.first
            every { allNetworks } returns all.map { it.first }.toTypedArray()
            every { getNetworkCapabilities(any()) } answers {
                val n = firstArg<Network>()
                (listOfNotNull(active) + all).firstOrNull { it.first == n }?.second
            }
        }
        return object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) cm else super.getSystemService(name)
        }
    }

    @Test
    fun `no networks means no vpn`() {
        val ctx = contextWith(null)
        assertFalse(VpnUtils.isVpnTransportActive(ctx))
        assertFalse(VpnUtils.isOtherVpnActive(ctx))
    }

    @Test
    fun `plain wifi is not a vpn`() {
        val ctx = contextWith(network(vpn = false), network(vpn = null))
        assertFalse(VpnUtils.isVpnTransportActive(ctx))
        assertFalse(VpnUtils.isOtherVpnActive(ctx))
    }

    @Test
    fun `a vpn network while our service is stopped is another app's vpn`() {
        val ctx = contextWith(null, network(vpn = false), network(vpn = true))
        assertTrue(VpnUtils.isVpnTransportActive(ctx))
        assertTrue(VpnUtils.isOtherVpnActive(ctx))
    }

    @Test
    fun `active network missing from allNetworks is still checked`() {
        val ctx = contextWith(network(vpn = true))
        assertTrue(VpnUtils.isVpnTransportActive(ctx))
        assertTrue(VpnUtils.isOtherVpnActive(ctx))
    }

    @Test
    fun `a lingering vpn right after our own stop is not a conflict`() {
        AdBlockVpnService.lastStoppedTimestamp = System.currentTimeMillis() - 5_000
        val ctx = contextWith(network(vpn = true))
        assertTrue(VpnUtils.isVpnTransportActive(ctx))
        assertFalse(VpnUtils.isOtherVpnActive(ctx))
    }

    @Test
    fun `our stop more than 20 seconds ago no longer masks a vpn`() {
        AdBlockVpnService.lastStoppedTimestamp = System.currentTimeMillis() - 25_000
        assertTrue(VpnUtils.isOtherVpnActive(contextWith(network(vpn = true))))
    }

    @Test
    fun `teardown wait returns at once without a vpn and times out with one`() = runTest {
        VpnUtils.awaitVpnTransportTeardown(contextWith(null), timeoutMs = 0)
        VpnUtils.awaitVpnTransportTeardown(contextWith(network(vpn = true)), timeoutMs = 0)
    }

    @Test
    fun `stop finalization runs the callback on the main thread`() {
        var finalized = false
        VpnUtils.scheduleStopFinalization(contextWith(null)) { finalized = true }
        val deadline = System.currentTimeMillis() + 5_000
        while (!finalized && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(finalized)
    }
}
