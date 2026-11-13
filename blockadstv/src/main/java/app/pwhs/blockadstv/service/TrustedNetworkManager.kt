package app.pwhs.blockadstv.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.pwhs.blockadstv.MainActivity
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.datastore.ProtectionPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Watches active Wi-Fi SSID and Ethernet connections on Android TV.
 * When "Pause on trusted networks" is enabled, auto-pauses BlockAds TV on a
 * trusted network and auto-resumes when leaving it.
 */
class TrustedNetworkManager(
    private val context: Context,
    private val protectionPrefs: ProtectionPreferences,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = evaluate()
        override fun onLost(network: Network) = evaluate()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluate()
    }

    fun start() {
        if (registered) return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            registered = true
            Timber.d("TrustedNetworkManager started")
            evaluate()
        } catch (e: Exception) {
            Timber.e(e, "TrustedNetworkManager register failed")
        }

        scope.launch {
            combine(
                protectionPrefs.pauseOnTrustedEnabled,
                protectionPrefs.trustedNetworks,
            ) { enabled, networks -> enabled to networks }
                .distinctUntilChanged()
                .collect { evaluate() }
        }
    }

    private fun evaluate() {
        scope.launch {
            lock.withLock {
                try {
                    val featureOn = protectionPrefs.getPauseOnTrustedEnabledSnapshot()
                    val trusted = protectionPrefs.getTrustedNetworksSnapshot()
                    if (!featureOn || trusted.isEmpty()) {
                        if (protectionPrefs.getPausedByTrustedSnapshot() && !ServiceController.isRunning) {
                            protectionPrefs.setPausedByTrusted(false)
                            cancelPausedNotification()
                            ServiceController.requestStart(context)
                        }
                        return@withLock
                    }

                    val currentNet = currentNetworkName(context)
                    val onTrusted = currentNet != null && currentNet in trusted
                    val running = ServiceController.isRunning
                    val pausedByUs = protectionPrefs.getPausedByTrustedSnapshot()

                    when {
                        onTrusted && running -> {
                            Timber.d("Trusted network '$currentNet' — pausing BlockAds TV")
                            protectionPrefs.setPausedByTrusted(true, currentNet)
                            ServiceController.requestStop(context)
                            showPausedNotification(currentNet)
                        }
                        !onTrusted && pausedByUs && !running -> {
                            Timber.d("Left trusted network (now '$currentNet') — resuming BlockAds TV")
                            protectionPrefs.setPausedByTrusted(false)
                            cancelPausedNotification()
                            ServiceController.requestStart(context)
                        }
                        running && pausedByUs -> {
                            protectionPrefs.setPausedByTrusted(false)
                            cancelPausedNotification()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "TrustedNetworkManager evaluate failed")
                }
            }
        }
    }

    private fun showPausedNotification(networkName: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.trusted_networks_title),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(false) }
                )
            }
            val tapIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = if (networkName.isNotEmpty()) {
                context.getString(R.string.trusted_networks_paused_text, networkName)
            } else {
                context.getString(R.string.trusted_networks_paused_text_generic)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.trusted_networks_paused_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(tapIntent)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.w(e, "showPausedNotification failed")
        }
    }

    private fun cancelPausedNotification() {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "blockadstv_trusted_channel"
        private const val NOTIFICATION_ID = 30
        const val ETHERNET_NETWORK_ID = "Ethernet"

        /**
         * Returns the active network identifier: [ETHERNET_NETWORK_ID] when on Ethernet,
         * the connected Wi-Fi SSID (without quotes) when on Wi-Fi with location permission,
         * or null if unavailable.
         */
        fun currentNetworkName(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val activeNetwork = cm.activeNetwork
            val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return ETHERNET_NETWORK_ID
            }
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }
            return currentWifiSsid(context)
        }

        private fun currentWifiSsid(context: Context): String? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            return try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
                @Suppress("DEPRECATION")
                val raw = wifi.connectionInfo?.ssid ?: return null
                val ssid = raw.trim('"')
                if (ssid.isEmpty() || ssid == "<unknown ssid>") null else ssid
            } catch (e: Exception) {
                Timber.w(e, "currentWifiSsid failed")
                null
            }
        }
    }
}
