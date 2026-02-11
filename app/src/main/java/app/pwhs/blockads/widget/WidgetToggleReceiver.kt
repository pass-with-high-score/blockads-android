package app.pwhs.blockads.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.service.AdBlockVpnService

/**
 * Internal (unexported) receiver that handles VPN toggle from the widget.
 * Kept separate from the exported AppWidgetProvider to prevent external apps
 * from toggling the VPN via broadcast.
 */
class WidgetToggleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WidgetToggle"
        const val ACTION_TOGGLE_VPN = "app.pwhs.blockads.widget.TOGGLE_VPN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_VPN) {
            toggleVpn(context)
            AdBlockWidgetProvider.sendUpdateBroadcast(context)
        }
    }

    private fun toggleVpn(context: Context) {
        if (AdBlockVpnService.isRunning) {
            val stopIntent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
            context.startService(stopIntent)
        } else {
            val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start VPN from widget, opening app", e)
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(appIntent)
            }
        }
    }
}
