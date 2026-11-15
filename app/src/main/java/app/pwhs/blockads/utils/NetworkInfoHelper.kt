package app.pwhs.blockads.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import app.pwhs.blockads.R
import app.pwhs.blockads.service.TrustedNetworkManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections

enum class CurrentNetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    DISCONNECTED
}

data class CurrentNetworkInfo(
    val name: String,
    val type: CurrentNetworkType,
    val ip: String?
)

object NetworkInfoHelper {

    fun getNetworkNameAndType(context: Context): Pair<String, CurrentNetworkType> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return context.getString(R.string.proxy_hero_network_disconnected) to CurrentNetworkType.DISCONNECTED

        val network = cm.activeNetwork
            ?: return context.getString(R.string.proxy_hero_network_disconnected) to CurrentNetworkType.DISCONNECTED

        val caps = cm.getNetworkCapabilities(network)
            ?: return context.getString(R.string.proxy_hero_network_disconnected) to CurrentNetworkType.DISCONNECTED

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = TrustedNetworkManager.currentSsid(context)
                val label = if (!ssid.isNullOrEmpty()) {
                    "${context.getString(R.string.proxy_hero_network_wifi)}: $ssid"
                } else {
                    context.getString(R.string.proxy_hero_network_wifi)
                }
                label to CurrentNetworkType.WIFI
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                context.getString(R.string.proxy_hero_network_cellular) to CurrentNetworkType.CELLULAR
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                context.getString(R.string.proxy_hero_network_ethernet) to CurrentNetworkType.ETHERNET
            }
            else -> {
                context.getString(R.string.proxy_hero_network_wifi) to CurrentNetworkType.WIFI
            }
        }
    }

    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in Collections.list(interfaces)) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            Timber.w(e, "getLocalIpAddress failed")
            null
        }
    }

    suspend fun fetchPublicIp(client: HttpClient): String? = withContext(Dispatchers.IO) {
        val ipv4 = withTimeoutOrNull(2500) {
            try {
                val res = client.get("https://api4.ipify.org")
                if (res.status.value in 200..299) res.bodyAsText().trim() else null
            } catch (_: Exception) {
                null
            }
        }
        if (!ipv4.isNullOrBlank()) return@withContext ipv4

        val dualStack = withTimeoutOrNull(2500) {
            try {
                val res = client.get("https://api.ipify.org")
                if (res.status.value in 200..299) res.bodyAsText().trim() else null
            } catch (_: Exception) {
                null
            }
        }
        if (!dualStack.isNullOrBlank()) return@withContext dualStack

        // Fallback to icanhazip.com
        withTimeoutOrNull(2500) {
            try {
                val res = client.get("https://icanhazip.com")
                if (res.status.value in 200..299) res.bodyAsText().trim() else null
            } catch (_: Exception) {
                null
            }
        }
    }

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
