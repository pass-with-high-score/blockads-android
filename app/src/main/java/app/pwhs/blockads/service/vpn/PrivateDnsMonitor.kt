package app.pwhs.blockads.service.vpn

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import timber.log.Timber

/**
 * Observes Android System Private DNS setting changes (Settings.Global "private_dns_mode"
 * and network LinkProperties) while the VPN service is alive.
 *
 * When the user switches Private DNS from a custom provider ("hostname") to Off ("off")
 * or Automatic ("opportunistic"), invokes [onPrivateDnsCleared] so the VPN tunnel can
 * restart and reclaim system DNS routing immediately.
 */
class PrivateDnsMonitor(
    private val context: Context,
    private val status: VpnStatusStore,
    private val isVpnRunning: () -> Boolean,
    private val onPrivateDnsCleared: () -> Unit,
) {
    companion object {
        private const val PRIVATE_DNS_MODE = "private_dns_mode"
        private const val PRIVATE_DNS_SPECIFIER = "private_dns_specifier"
        private const val MODE_HOSTNAME = "hostname"
        private const val MODE_OFF = "off"
        private const val MODE_OPPORTUNISTIC = "opportunistic"
    }

    private var registered = false

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            refresh(null)
        }
    }

    fun start() {
        if (registered || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val resolver = context.contentResolver
            resolver.registerContentObserver(
                Settings.Global.getUriFor(PRIVATE_DNS_MODE),
                false,
                settingsObserver
            )
            resolver.registerContentObserver(
                Settings.Global.getUriFor(PRIVATE_DNS_SPECIFIER),
                false,
                settingsObserver
            )
            registered = true
            refresh(null)
        }.onFailure { e ->
            Timber.w(e, "Failed to register Private DNS ContentObserver")
        }
    }

    fun stop() {
        if (!registered) return
        runCatching {
            context.contentResolver.unregisterContentObserver(settingsObserver)
        }
        registered = false
    }

    fun refresh(linkProperties: LinkProperties? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            status.privateDnsStrict.value = false
            return
        }
        val wasStrict = status.privateDnsStrict.value
        val nowStrict = resolveIsPrivateDnsStrict(linkProperties)
        status.privateDnsStrict.value = nowStrict

        if (wasStrict && !nowStrict && isVpnRunning()) {
            Timber.i("System Private DNS changed from Custom to Off/Auto — restarting VPN to reclaim DNS")
            onPrivateDnsCleared()
        }
    }

    private fun resolveIsPrivateDnsStrict(hintLinkProperties: LinkProperties?): Boolean {
        val mode = runCatching {
            Settings.Global.getString(context.contentResolver, PRIVATE_DNS_MODE)
        }.getOrNull()?.trim()?.lowercase()

        when (mode) {
            MODE_HOSTNAME -> return true
            MODE_OFF, MODE_OPPORTUNISTIC -> return false
        }

        if (hintLinkProperties != null) {
            return hintLinkProperties.privateDnsServerName != null
        }

        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching false
            val physicalLp = cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }?.let { cm.getLinkProperties(it) }

            val lp = physicalLp ?: cm.activeNetwork?.let { cm.getLinkProperties(it) }
            lp?.privateDnsServerName != null
        }.getOrDefault(false)
    }
}
