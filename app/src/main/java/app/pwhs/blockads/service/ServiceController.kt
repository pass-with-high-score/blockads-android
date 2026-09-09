package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Unified service controller that dispatches restart/stop requests
 * to the correct service based on the current routing mode.
 *
 * This avoids the need to check routing mode at every ViewModel callsite.
 */
object ServiceController {

    /**
     * Request a restart of whichever ad-blocking service is currently running.
     * If Root Proxy mode is active, restarts RootProxyService.
     * If VPN mode is active, restarts AdBlockVpnService.
     * Safe to call from any thread.
     */
    fun requestRestart(context: Context) {
        // Check both services — at least one might be running
        if (RootProxyService.isRunning) {
            RootProxyService.requestRestart(context)
        }
        if (AdBlockVpnService.isRunning) {
            AdBlockVpnService.requestRestart(context)
        }
    }

    /**
     * Start the VPN or Root Proxy depending on AppPreferences.
     */
    fun requestStart(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val appPrefs = AppPreferences(context)
            val routingMode = appPrefs.routingMode.first()

            if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                RootProxyService.start(context)
            } else {
                AdBlockVpnService.start(context)
            }
        }
    }

    /**
     * Stop whichever service is currently running.
     */
    fun requestStop(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val appPrefs = AppPreferences(context)
            val isLocked = appPrefs.lockdownEnabled.first()
            if (isLocked) {
                Timber.w("Stop request ignored: System is in Lockdown Mode.")
                return@launch
            }
            if (RootProxyService.isRunning) {
                RootProxyService.stop(context)
            }
            if (AdBlockVpnService.isRunning) {
                AdBlockVpnService.stop(context)
            }
        }
    }
}
