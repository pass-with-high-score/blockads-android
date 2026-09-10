package app.pwhs.blockads.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.VpnState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object VpnUtils {
    /**
     * Returns true only if a **third-party** VPN is currently active.
     *
     * When BlockAds stops its own VPN, the OS VPN transport can linger
     * for 10-15 seconds after we close the TUN fd.  During that window
     * AdBlockVpnService.isRunning is already false (state == STOPPED),
     * so we must also check whether we recently owned the VPN
     * (state == STOPPING) to avoid a false-positive conflict dialog.
     */
    fun isOtherVpnActive(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val allNetworks = connectivityManager.allNetworks.toMutableList()
        if (activeNetwork != null && !allNetworks.contains(activeNetwork)) {
            allNetworks.add(0, activeNetwork)
        }

        for (network in allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                // If our service is in ANY non-STOPPED state the VPN transport
                // is still ours — even if the OS hasn't fully torn it down yet.
                val ourState = AdBlockVpnService.state.value
                val oursRecentlyActive = ourState != VpnState.STOPPED
                // Also check a timestamp-based window: if we stopped very recently
                // (< 20s ago), the lingering transport is almost certainly ours.
                val stoppedRecently = AdBlockVpnService.lastStoppedTimestamp > 0L &&
                        System.currentTimeMillis() - AdBlockVpnService.lastStoppedTimestamp < 20_000L
                if (!oursRecentlyActive && !stoppedRecently) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Returns true if any network currently has TRANSPORT_VPN.
     * Android's status bar VPN key icon is displayed whenever this is true.
     */
    fun isVpnTransportActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork
        val allNetworks = cm.allNetworks.toMutableList()
        if (activeNetwork != null && !allNetworks.contains(activeNetwork)) {
            allNetworks.add(0, activeNetwork)
        }
        return allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /**
     * Suspends until the OS has completely dropped the VPN transport and removed the key icon.
     */
    suspend fun awaitVpnTransportTeardown(context: Context, timeoutMs: Long = 6000L) {
        val startWait = android.os.SystemClock.elapsedRealtime()
        while (isVpnTransportActive(context) &&
            android.os.SystemClock.elapsedRealtime() - startWait < timeoutMs
        ) {
            kotlinx.coroutines.delay(100L)
        }
    }

    /**
     * Runs awaitVpnTransportTeardown on IO dispatcher and dispatches onFinalized on Main dispatcher.
     */
    fun scheduleStopFinalization(context: Context, onFinalized: () -> Unit) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            awaitVpnTransportTeardown(context)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onFinalized()
            }
        }
    }
}
