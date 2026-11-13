package app.pwhs.blockadstv.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import timber.log.Timber

/**
 * Monitors network connectivity changes and notifies when network is available or lost.
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit,
    private val onLinkPropertiesChanged: ((LinkProperties) -> Unit)? = null,
    private val onNetworkActiveChanged: ((Network?) -> Unit)? = null,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var isRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Timber.d("Network available: $network")
            onNetworkActiveChanged?.invoke(network)
            onNetworkAvailable()
        }

        override fun onLost(network: Network) {
            Timber.d("Network lost: $network")
            onNetworkActiveChanged?.invoke(connectivityManager.activeNetwork)
            onNetworkLost()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            Timber.d("Network capabilities changed: hasInternet=$hasInternet, validated=$hasValidated")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties)
            onLinkPropertiesChanged?.invoke(linkProperties)
        }
    }

    fun startMonitoring() {
        if (isRegistered) {
            Timber.w("Network monitoring already started")
            return
        }

        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            isRegistered = true
            Timber.d("Network monitoring started")
        } catch (e: Exception) {
            Timber.e("Failed to register network callback: $e")
        }
    }

    fun stopMonitoring() {
        if (!isRegistered) return

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            Timber.d("Network monitoring stopped")
        } catch (e: Exception) {
            Timber.d("Failed to unregister network callback: $e")
        }
    }

    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
