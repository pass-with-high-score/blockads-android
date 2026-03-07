package app.pwhs.blockads.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = AppPreferences(context)
        val autoReconnect = runBlocking { prefs.autoReconnect.first() }
        val wasEnabled = runBlocking { prefs.vpnEnabled.first() }

        if (autoReconnect && wasEnabled) {
            Timber.d("Auto-reconnecting VPN after boot")
            val serviceIntent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
