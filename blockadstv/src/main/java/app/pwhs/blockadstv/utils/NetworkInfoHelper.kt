package app.pwhs.blockadstv.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import timber.log.Timber
import java.net.Inet6Address

object NetworkInfoHelper {

    /**
     * Determines whether the physical active network has a routable IPv6 address
     * (non-link-local, non-loopback) and an IPv6 route.
     */
    fun hasRoutableIpv6(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val lp = cm.getLinkProperties(network) ?: return false
            hasRoutableIpv6(lp)
        } catch (e: Exception) {
            Timber.w(e, "hasRoutableIpv6 failed")
            false
        }
    }

    fun hasRoutableIpv6(lp: LinkProperties?): Boolean {
        if (lp == null) return false
        val hasGlobalV6 = lp.linkAddresses.any { linkAddr ->
            val addr = linkAddr.address
            addr is Inet6Address && !addr.isLinkLocalAddress && !addr.isLoopbackAddress
        }
        val hasV6Route = lp.routes.any { route ->
            val dest = route.destination.address
            dest is Inet6Address && (route.isDefaultRoute || !dest.isLinkLocalAddress)
        }
        return hasGlobalV6 && hasV6Route
    }
}
