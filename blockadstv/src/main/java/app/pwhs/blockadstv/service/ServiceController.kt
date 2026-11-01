package app.pwhs.blockadstv.service

import android.content.Context
import app.pwhs.blockadstv.data.datastore.TvPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object ServiceController {

    fun requestStart(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val tvPrefs = TvPreferences(context)
            val routingMode = tvPrefs.routingMode.first()

            if (routingMode == TvPreferences.ROUTING_MODE_ROOT) {
                RootProxyService.start(context)
            } else {
                TvVpnService.start(context)
            }
        }
    }

    fun requestStop(context: Context) {
        if (RootProxyService.isRunning) {
            RootProxyService.stop(context)
        }
        if (TvVpnService.isRunning) {
            TvVpnService.stop(context)
        }
    }

    fun requestRestart(context: Context) {
        if (RootProxyService.isRunning) {
            RootProxyService.requestRestart(context)
        }
        if (TvVpnService.isRunning) {
            TvVpnService.requestRestart(context)
        }
    }

    val isRunning: Boolean
        get() = RootProxyService.isRunning || TvVpnService.isRunning

    val activeStartTimestamp: Long
        get() = when {
            RootProxyService.isRunning -> RootProxyService.startTimestamp
            TvVpnService.isRunning -> TvVpnService.startTimestamp
            else -> 0L
        }
}
