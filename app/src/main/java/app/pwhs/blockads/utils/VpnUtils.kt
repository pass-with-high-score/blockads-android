package app.pwhs.blockads.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.VpnState

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
        val allNetworks = connectivityManager.allNetworks

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
}
