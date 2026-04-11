package app.pwhs.blockadstv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.service.TvVpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = TvPreferences(context)
            val startOnBoot = runBlocking { prefs.startOnBoot.first() }
            if (startOnBoot) {
                Timber.d("Boot completed, starting VPN service")
                TvVpnService.start(context)
            }
        }
    }
}
