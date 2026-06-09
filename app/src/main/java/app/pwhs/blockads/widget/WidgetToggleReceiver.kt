package app.pwhs.blockads.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.utils.VpnUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Internal (unexported) receiver that handles VPN toggle from the widget.
 * Kept separate from the exported AppWidgetProvider to prevent external apps
 * from toggling the VPN via broadcast.
 */
class WidgetToggleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE_VPN = "app.pwhs.blockads.widget.TOGGLE_VPN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_VPN) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    toggleVpn(context)
                    AdBlockWidgetProvider.sendUpdateBroadcast(context)
                } catch (e: Exception) {
                    Timber.e(e, "Error toggling VPN from widget receiver")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun toggleVpn(context: Context) {
        // Stop logic: evaluate running status of both services
        if (AdBlockVpnService.isRunning || RootProxyService.isRunning) {
            // Stop AdBlockVpnService if running
            if (AdBlockVpnService.isRunning) {
                val stopIntent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_STOP
                }
                context.startService(stopIntent)
            }
            
            // Stop RootProxyService if running
            if (RootProxyService.isRunning) {
                val stopIntent = Intent(context, RootProxyService::class.java).apply {
                    action = RootProxyService.ACTION_STOP
                }
                context.startService(stopIntent)
            }
        } else {
            val appPrefs = AppPreferences(context)
            val routingMode = appPrefs.routingMode.first()
            val isRootMode = routingMode == AppPreferences.ROUTING_MODE_ROOT

            if (!isRootMode && VpnUtils.isOtherVpnActive(context)) {
                Timber.w("Another VPN is active, dropping widget connection request")
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_SHOW_VPN_CONFLICT_DIALOG, true)
                }
                context.startActivity(appIntent)
                return
            }

            // Start logic: determine the service class and action dynamically based on routing mode
            val targetClass = if (isRootMode) RootProxyService::class.java else AdBlockVpnService::class.java
            val targetAction = if (isRootMode) RootProxyService.ACTION_START else AdBlockVpnService.ACTION_START

            val startIntent = Intent(context, targetClass).apply {
                action = targetAction
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            } catch (e: Exception) {
                Timber.w(e, "Cannot start VPN from widget, opening app")
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(appIntent)
            }
        }
    }
}
