package app.pwhs.blockads.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import app.pwhs.blockads.R
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import app.pwhs.blockads.data.datastore.AppPreferences
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
 * Trusted Wi-Fi networks (#197).
 *
 * Watches the connected Wi-Fi SSID and, when "Pause on trusted networks" is
 * enabled, auto-pauses BlockAds on a trusted SSID and auto-resumes when the
 * device leaves it. Registered once from [BlockAdsApplication] so it runs for
 * the whole process lifetime.
 *
 * Limitation: auto-resume only fires while the app process is alive. While
 * protection is ON the foreground service keeps it alive, so entering a
 * trusted network reliably pauses; resuming after the process was killed
 * happens on next app launch / boot. Documented in the UI.
 */
class TrustedNetworkManager(
    private val context: Context,
    private val appPrefs: AppPreferences,
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
                .build()
            cm.registerNetworkCallback(request, callback)
            registered = true
            Timber.d("TrustedNetworkManager started")
            evaluate()
        } catch (e: Exception) {
            Timber.e(e, "TrustedNetworkManager register failed")
        }

        // Re-evaluate when the settings change (toggle flipped or an SSID
        // added/removed) — otherwise enabling the feature while already
        // connected to a trusted network wouldn't pause until the next
        // network change.
        scope.launch {
            combine(
                appPrefs.pauseOnTrustedEnabled,
                appPrefs.trustedSsids
            ) { enabled, ssids -> enabled to ssids }
                .distinctUntilChanged()
                .collect { evaluate() }
        }
    }

    private fun evaluate() {
        scope.launch {
            lock.withLock {
                try {
                    // If the feature was turned off (or all SSIDs removed) while
                    // we had auto-paused, resume protection and clear the notice.
                    val featureOn = appPrefs.getPauseOnTrustedEnabledSnapshot()
                    val trusted = appPrefs.getTrustedSsidsSnapshot()
                    if (!featureOn || trusted.isEmpty()) {
                        if (appPrefs.getPausedByTrustedSnapshot() &&
                            !(AdBlockVpnService.isRunning || RootProxyService.isRunning)
                        ) {
                            appPrefs.setPausedByTrusted(false)
                            cancelPausedNotification()
                            ServiceController.requestStart(context)
                        }
                        return@withLock
                    }

                    val ssid = currentSsid(context)
                    val onTrusted = ssid != null && ssid in trusted
                    val running = AdBlockVpnService.isRunning || RootProxyService.isRunning
                    val pausedByUs = appPrefs.getPausedByTrustedSnapshot()
                    Timber.d("TrustedNet evaluate: ssid=$ssid trusted=$trusted onTrusted=$onTrusted running=$running pausedByUs=$pausedByUs")

                    when {
                        // Entered a trusted network while protected → pause.
                        onTrusted && running -> {
                            Timber.d("Trusted network '$ssid' — pausing BlockAds")
                            appPrefs.setPausedByTrusted(true, ssid ?: "")
                            ServiceController.requestPause(context)
                            showPausedNotification(ssid ?: "")
                        }
                        // Left the trusted network and we had paused → resume.
                        !onTrusted && pausedByUs && !running -> {
                            Timber.d("Left trusted network (now '$ssid') — resuming BlockAds")
                            appPrefs.setPausedByTrusted(false)
                            cancelPausedNotification()
                            ServiceController.requestStart(context)
                        }
                        // User manually started while on a trusted net, or any
                        // other state — clear the stale pause flag.
                        running && pausedByUs -> {
                            appPrefs.setPausedByTrusted(false)
                            cancelPausedNotification()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "TrustedNetworkManager evaluate failed")
                }
            }
        }
    }

    private fun showPausedNotification(ssid: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.trusted_networks_title),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { setShowBadge(false) }
                )
            }
            val tapIntent = PendingIntent.getActivity(
                context, 0,
                android.content.Intent(context, app.pwhs.blockads.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val text = if (ssid.isNotEmpty())
                context.getString(R.string.trusted_networks_paused_text, ssid)
            else context.getString(R.string.trusted_networks_paused_text_generic)
            val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield_off)
                .setContentTitle(context.getString(R.string.trusted_networks_paused_title))
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(false)
                .setAutoCancel(false)
                .setContentIntent(tapIntent)
                .build()
            nm.notify(NOTIFICATION_ID, n)
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
        private const val CHANNEL_ID = "blockads_trusted_channel"
        private const val NOTIFICATION_ID = 30

        /**
         * Returns the connected Wi-Fi SSID (without surrounding quotes), or null
         * if not on Wi-Fi / unavailable / location permission missing.
         */
        fun currentSsid(context: Context): String? {
            // SSID requires location permission on Android 10+.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) return null
            return try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
                @Suppress("DEPRECATION")
                val raw = wifi.connectionInfo?.ssid ?: return null
                val ssid = raw.trim('"')
                if (ssid.isEmpty() || ssid == "<unknown ssid>") null else ssid
            } catch (e: Exception) {
                Timber.w(e, "currentSsid failed")
                null
            }
        }
    }
}
