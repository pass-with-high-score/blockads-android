package app.pwhs.blockads.service.vpn

import android.content.Context
import android.os.PowerManager
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.NetworkMonitor
import app.pwhs.blockads.utils.BatteryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class VpnConnectionSupervisor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appPrefs: AppPreferences,
    private val batteryMonitor: BatteryMonitor,
    private val isRunningProvider: () -> Boolean,
    private val isIdleProvider: () -> Boolean,
    private val onTearDownForRestart: suspend () -> Unit,
    private val onStartVpn: () -> Unit,
    private val onPhaseChanged: (String) -> Unit,
    private val onRefreshStats: suspend () -> Unit,
    private val onUpdateNotification: () -> Unit,
    private val onLinkPropertiesChanged: (android.net.LinkProperties?) -> Unit
) {

    companion object {
        private const val NETWORK_STABILIZATION_DELAY_MS = 2000L
        private const val BATTERY_CHECK_INTERVAL_MS = 5 * 60 * 1000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 30_000L
    }

    val networkAvailableFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var networkMonitor: NetworkMonitor? = null
    private var networkSwitchJob: Job? = null
    private var batteryMonitoringJob: Job? = null
    private var notificationUpdateJob: Job? = null

    fun initializeNetworkMonitor() {
        networkMonitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = { onNetworkAvailable() },
            onNetworkLost = { Timber.d("Network lost") },
            onLinkPropertiesChanged = onLinkPropertiesChanged
        )
    }

    fun startNetworkMonitoring() {
        networkMonitor?.startMonitoring()
    }

    fun stopNetworkMonitoring() {
        networkMonitor?.stopMonitoring()
    }

    fun isNetworkAvailable(): Boolean {
        return networkMonitor?.isNetworkAvailable() ?: true
    }

    fun onNetworkAvailable() {
        Timber.d("Network available - checking VPN status")
        networkAvailableFlow.tryEmit(Unit)

        networkSwitchJob?.cancel()
        networkSwitchJob = scope.launch {
            val autoReconnect = appPrefs.autoReconnect.first()
            val vpnWasEnabled = appPrefs.vpnEnabled.first()
            val delayEnabled = appPrefs.networkSwitchDelayEnabled.first()
            val delaySec = appPrefs.networkSwitchDelaySec.first()

            if (delayEnabled && isRunningProvider()) {
                Timber.d("Network changed while VPN running — pausing for ${delaySec}s")
                onTearDownForRestart()
                for (remaining in delaySec downTo 1) {
                    onPhaseChanged(context.getString(R.string.vpn_network_switch_waiting, remaining))
                    onUpdateNotification()
                    delay(1000L)
                }
                onPhaseChanged("")
                onStartVpn()
                return@launch
            }

            if (autoReconnect && vpnWasEnabled && isIdleProvider()) {
                Timber.d("Auto-reconnecting VPN after network became available")
                if (delayEnabled) {
                    for (remaining in delaySec downTo 1) {
                        onPhaseChanged(context.getString(R.string.vpn_network_switch_waiting, remaining))
                        onUpdateNotification()
                        delay(1000L)
                    }
                    onPhaseChanged("")
                } else {
                    delay(NETWORK_STABILIZATION_DELAY_MS)
                }
                if (isIdleProvider()) {
                    onStartVpn()
                }
            }
        }
    }

    fun cancelNetworkSwitch() {
        networkSwitchJob?.cancel()
        networkSwitchJob = null
    }

    fun startPeriodicMonitoring() {
        startBatteryMonitoring()
        startNotificationUpdates()
    }

    fun stopPeriodicMonitoring() {
        stopBatteryMonitoring()
        stopNotificationUpdates()
    }

    private fun startBatteryMonitoring() {
        batteryMonitoringJob?.cancel()
        batteryMonitoringJob = scope.launch {
            while (isRunningProvider()) {
                try {
                    delay(BATTERY_CHECK_INTERVAL_MS)
                    if (isRunningProvider()) {
                        batteryMonitor.logBatteryStatus()
                    }
                } catch (e: Exception) {
                    Timber.e("Error monitoring battery: $e")
                    break
                }
            }
        }
    }

    private fun stopBatteryMonitoring() {
        batteryMonitoringJob?.cancel()
        batteryMonitoringJob = null
    }

    private fun startNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = scope.launch {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            while (isRunningProvider()) {
                try {
                    onRefreshStats()
                    delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                    if (isRunningProvider() && powerManager?.isInteractive == true) {
                        onUpdateNotification()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating notification")
                    break
                }
            }
        }
    }

    private fun stopNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }
}
