package app.pwhs.blockads.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
                val wasEnabled = prefs.vpnEnabled.first()
                val routingMode = prefs.routingMode.first()
                val isLocked = prefs.lockdownEnabled.first()

                // Root Mode: iptables rules are volatile (cleared on reboot).
                // Re-apply rules by starting RootProxyService.
                // Also restarts after app update (MY_PACKAGE_REPLACED).
                if (isLocked || (autoReconnect && wasEnabled)) {
                    val trigger = if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "app update" else "boot"
                    if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                        Timber.d("Auto-starting Root Proxy mode after $trigger")
                        val serviceIntent = Intent(context, RootProxyService::class.java).apply {
                            action = RootProxyService.ACTION_START
                            putExtra(RootProxyService.EXTRA_STARTED_FROM_BOOT, true)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        Timber.d("Auto-reconnecting VPN after $trigger")
                        val serviceIntent = Intent(context, AdBlockVpnService::class.java).apply {
                            action = AdBlockVpnService.ACTION_START
                            putExtra(AdBlockVpnService.EXTRA_STARTED_FROM_BOOT, true)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error starting service after boot")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
