package app.pwhs.blockads.service.vpn

import android.content.Context
import android.net.LinkProperties
import android.net.Network
import android.os.PowerManager
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.ConnectionQualityProbe
import app.pwhs.blockads.service.ConnectionStatus
import app.pwhs.blockads.service.NetworkMonitor
import app.pwhs.blockads.utils.BatteryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val socketProtector: (java.net.Socket) -> Boolean,
    private val isEngineRunning: () -> Boolean,
    private val onTearDownForRestart: suspend () -> Unit,
    private val onStartVpn: () -> Unit,
    private val onPhaseChanged: (String) -> Unit,
    private val onRefreshStats: suspend () -> Unit,
    private val onUpdateNotification: () -> Unit,
    private val onLinkPropertiesChanged: (LinkProperties?) -> Unit,
    private val onPhysicalNetworkLostChanged: (Boolean) -> Unit,
    private val onNetworkActiveChanged: (Network?) -> Unit,
    private val onRequestRestart: () -> Unit,
    private val blockedCountFlowProvider: (() -> Flow<Int>)? = null,
    private val onBlockedCountUpdated: ((Int) -> Unit)? = null
) : VpnNetworkWatch {

    companion object {
        private const val NETWORK_STABILIZATION_DELAY_MS = 2000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 30_000L
        private const val NOTIFICATION_THROTTLE_MS = 1_500L
        private const val LOW_BATTERY_NOTIFICATION_THROTTLE_MS = 15_000L
        private const val CONNECTION_PROBE_INTERVAL_MS = 60_000L
        private const val LOW_BATTERY_PROBE_INTERVAL_MS = 3 * 60 * 1000L
    }

    override val networkAvailableFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var networkMonitor: NetworkMonitor? = null
    private var networkSwitchJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var connectionQualityProbe: ConnectionQualityProbe? = null
    private var connectionProbeJob: Job? = null

    fun initializeNetworkMonitor() {
        networkMonitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = {
                onPhysicalNetworkLostChanged(false)
                onNetworkAvailable()
            },
            onNetworkLost = {
                Timber.d("Network lost")
                onPhysicalNetworkLostChanged(true)
            },
            onLinkPropertiesChanged = onLinkPropertiesChanged,
            onNetworkActiveChanged = onNetworkActiveChanged
        )
    }

    override fun startNetworkMonitoring() {
        networkMonitor?.startMonitoring()
    }

    override fun stopNetworkMonitoring() {
        networkMonitor?.stopMonitoring()
    }

    override fun isNetworkAvailable(): Boolean {
        return networkMonitor?.isNetworkAvailable() ?: true
    }

    fun onNetworkAvailable() {
        Timber.d("Network available - checking VPN status")
        networkAvailableFlow.tryEmit(Unit)

        networkSwitchJob?.cancel()
        networkSwitchJob = scope.launch {
            val autoReconnect = appPrefs.autoReconnect.first()
            val vpnWasEnabled = appPrefs.vpnEnabled.first()

            if (autoReconnect && vpnWasEnabled && isIdleProvider()) {
                Timber.d("Auto-reconnecting VPN after network became available")
                delay(NETWORK_STABILIZATION_DELAY_MS)
                if (isIdleProvider()) {
                    onStartVpn()
                }
            }
        }
    }

    override fun cancelNetworkSwitch() {
        networkSwitchJob?.cancel()
        networkSwitchJob = null
    }

    override fun startPeriodicMonitoring() {
        startNotificationUpdates()
        startConnectionProbing()
    }

    override fun stopPeriodicMonitoring() {
        stopNotificationUpdates()
        stopConnectionProbing()
    }

    private fun startConnectionProbing() {
        connectionProbeJob?.cancel()
        connectionQualityProbe = ConnectionQualityProbe(socketProtector, isEngineRunning)

        connectionProbeJob = scope.launch {
            var consecutiveVpnStalls = 0
            while (isRunningProvider()) {
                val intervalMs = if (batteryMonitor.isLowBatteryOrPowerSave()) {
                    LOW_BATTERY_PROBE_INTERVAL_MS
                } else {
                    CONNECTION_PROBE_INTERVAL_MS
                }
                delay(intervalMs)
                if (!isRunningProvider()) break

                val probe = connectionQualityProbe ?: break
                val result = probe.runDiagnosis()
                Timber.d("ConnectionQualityProbe result: ${result.status} (physical=${result.physicalOk}, engine=${result.engineOk}, latency=${result.latencyMs}ms)")

                when (result.status) {
                    ConnectionStatus.NO_PHYSICAL_INTERNET -> {
                        consecutiveVpnStalls = 0
                        onPhysicalNetworkLostChanged(true)
                    }
                    ConnectionStatus.HEALTHY -> {
                        consecutiveVpnStalls = 0
                        onPhysicalNetworkLostChanged(false)
                    }
                    ConnectionStatus.VPN_TUNNEL_STALLED -> {
                        onPhysicalNetworkLostChanged(false)
                        consecutiveVpnStalls++
                        Timber.w("Tunnel engine not running (consecutive failures=$consecutiveVpnStalls)")
                        if (consecutiveVpnStalls >= 2) {
                            Timber.w("Tunnel engine stopped while physical internet is healthy - restarting VPN session")
                            consecutiveVpnStalls = 0
                            onRequestRestart()
                        }
                    }
                }
            }
        }
    }

    private fun stopConnectionProbing() {
        connectionProbeJob?.cancel()
        connectionProbeJob = null
        connectionQualityProbe = null
    }

    private fun startNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = scope.launch {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val flowProvider = blockedCountFlowProvider
            val onCount = onBlockedCountUpdated
            if (flowProvider != null && onCount != null) {
                try {
                    flowProvider()
                        .distinctUntilChanged()
                        .conflate()
                        .collect { count ->
                            if (!isRunningProvider()) return@collect
                            onCount(count)
                            if (powerManager?.isInteractive == true) {
                                onUpdateNotification()
                                val throttleMs = if (batteryMonitor.isLowBatteryOrPowerSave()) {
                                    LOW_BATTERY_NOTIFICATION_THROTTLE_MS
                                } else {
                                    NOTIFICATION_THROTTLE_MS
                                }
                                delay(throttleMs)
                            }
                        }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Timber.e(e, "Error observing blocked count for notification")
                    }
                }
                return@launch
            }
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
