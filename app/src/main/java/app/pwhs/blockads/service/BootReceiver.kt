package app.pwhs.blockads.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        val prefs = AppPreferences(context)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val autoReconnect = prefs.autoReconnect.first()
                val protectionDesired = prefs.protectionDesired.first()
                val routingMode = prefs.routingMode.first()

                if (autoReconnect && protectionDesired) {
                    val trigger = if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "app update" else "boot"
                    Timber.d("Auto-starting protection after $trigger, routingMode=$routingMode")
                    ServiceController.requestStartNow(context, startedFromBoot = true)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error starting service after boot")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
