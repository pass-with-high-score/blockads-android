package app.pwhs.blockads.service.vpn

import android.os.ParcelFileDescriptor
import app.pwhs.blockads.service.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide VPN status, exposed to the rest of the app through AdBlockVpnService's companion. */
class VpnStatusStore {
    val state = MutableStateFlow(VpnState.STOPPED)
    var startTimestamp = 0L
    var lastStoppedTimestamp = 0L
    val privateDnsStrict = MutableStateFlow(false)
}

/** Android side effects [VpnSessionController] needs from its service. */
interface VpnSessionHost {
    fun getString(resId: Int): String
    fun enterForeground()
    fun updateNotification()
    fun showRevokedNotification()
    fun showStoppedNotification()
    fun stopForeground(removeNotification: Boolean)
    fun stopSelf()
    fun refreshPrivateDnsState()
    fun broadcastWidgetUpdate()
    fun logBatteryStatus()
    fun scheduleStopFinalization(onFinalized: () -> Unit)
    fun onFullyStopped()
}

/** The Go engine as a session sees it: configure, attach to the TUN, stop. */
interface VpnSessionEngine {
    suspend fun prepareStartupConfig(): StartupConfig
    suspend fun configure(config: StartupConfig)
    fun startFilterUpdateWatcher(scope: CoroutineScope)
    suspend fun startTunnel(vpnInterface: ParcelFileDescriptor, resolvedWgConfigJson: String, httpsFilteringEnabled: Boolean)
    fun stop()
}

/** The parts of [VpnConnectionSupervisor] a session drives. */
interface VpnNetworkWatch {
    val networkAvailableFlow: Flow<Unit>
    fun startNetworkMonitoring()
    fun stopNetworkMonitoring()
    fun isNetworkAvailable(): Boolean
    fun cancelNetworkSwitch()
    fun startPeriodicMonitoring()
    fun stopPeriodicMonitoring()
}
