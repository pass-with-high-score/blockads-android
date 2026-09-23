package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {

    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val request = slot<NetworkRequest>()
    private val active: Network = mockk()
    private var activeNetwork: Network? = active
    private var caps: NetworkCapabilities? = null
    private val cm: ConnectivityManager = mockk {
        every { registerNetworkCallback(capture(request), capture(callback)) } just runs
        every { unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just runs
        every { activeNetwork } answers { this@NetworkMonitorTest.activeNetwork }
        every { getNetworkCapabilities(any()) } answers { caps }
    }
    private val context: Context = mockk { every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm }

    private val events = mutableListOf<String>()
    private val activeChanges = mutableListOf<Network?>()
    private val monitor = NetworkMonitor(
        context,
        onNetworkAvailable = { events += "available" },
        onNetworkLost = { events += "lost" },
        onLinkPropertiesChanged = { events += "link" },
        onNetworkActiveChanged = { activeChanges += it },
    )

    private fun capsWith(vararg capabilities: Int): NetworkCapabilities = mockk {
        every { hasCapability(any()) } answers { firstArg<Int>() in capabilities }
    }

    @Test
    fun `registers once for validated internet and unregisters once`() {
        monitor.startMonitoring()
        monitor.startMonitoring()
        verify(exactly = 1) { cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) }
        assertTrue(request.captured.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

        monitor.stopMonitoring()
        monitor.stopMonitoring()
        verify(exactly = 1) { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `callbacks forward availability, loss and link changes`() {
        monitor.startMonitoring()
        val network: Network = mockk()
        callback.captured.onAvailable(network)
        callback.captured.onCapabilitiesChanged(network, capsWith(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        callback.captured.onLinkPropertiesChanged(network, LinkProperties())
        callback.captured.onLost(network)

        assertEquals(listOf("available", "link", "lost"), events)
        assertEquals(listOf(network, active), activeChanges)
    }

    @Test
    fun `registration failure is swallowed`() {
        every { cm.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } throws SecurityException()
        monitor.startMonitoring()
        monitor.stopMonitoring()
        verify(exactly = 0) { cm.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `availability needs an active, validated internet network`() {
        activeNetwork = null
        assertFalse(monitor.isNetworkAvailable())
        activeNetwork = active
        assertFalse(monitor.isNetworkAvailable())
        caps = capsWith(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        assertFalse(monitor.isNetworkAvailable())
        caps = capsWith(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertTrue(monitor.isNetworkAvailable())
    }
}
