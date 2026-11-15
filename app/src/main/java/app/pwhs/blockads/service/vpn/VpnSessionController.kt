package app.pwhs.blockads.service.vpn

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.FirewallManager
import app.pwhs.blockads.service.VpnRetryManager
import app.pwhs.blockads.service.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * The VPN session state machine: start job with its establish/retry loop, stop, restart and revoke.
 * AdBlockVpnService is a thin shell that forwards its lifecycle here.
 */
class VpnSessionController(
    private val scope: CoroutineScope,
    private val status: VpnStatusStore,
    private val host: VpnSessionHost,
    private val engine: VpnSessionEngine,
    private val network: VpnNetworkWatch,
    private val appPrefs: AppPreferences,
    private val dnsLogDao: DnsLogDao,
    private val nextMilestoneThreshold: (Long) -> Long?,
    private val establishTunnel: (Set<String>) -> TunnelResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {

    companion object {
        private const val RESTART_CLEANUP_DELAY_MS = 1000L
        private const val GO_STOP_TIMEOUT_MS = 300L
    }

    val retryManager = VpnRetryManager(maxRetries = 5, maxDelayMs = 60000L)

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var resolvedWgConfigJson: String = ""
    var vpnStartTime: Long = 0L
        private set
    @Volatile var todayBlockedCount: Int = 0
    private val allTimeBlockedCount = AtomicLong(0)
    @Volatile private var nextMilestone: Long? = null
    @Volatile var isReconnecting = false
        private set
    @Volatile var isPhysicalNetworkLost = false
        private set
    @Volatile var connectingPhase: String = ""

    private fun setPhase(phase: String) {
        connectingPhase = phase
        status.phase.value = phase
    }
    @Volatile var firewallManager: FirewallManager? = null
        private set

    val state: VpnState get() = status.state.value
    val isRunning: Boolean get() = state == VpnState.RUNNING
    val isConnecting: Boolean get() = state == VpnState.STARTING
    val isRestarting: Boolean get() = state == VpnState.RESTARTING
    val isStopping: Boolean get() = state == VpnState.STOPPING

    /** Supervisor-driven start after a network switch or auto-reconnect. */
    fun startFromSupervisor() {
        retryManager.reset()
        start()
        isReconnecting = false
    }

    fun onPhysicalNetworkLostChanged(lost: Boolean) {
        if (status.state.value == VpnState.STOPPING) return
        if (isPhysicalNetworkLost != lost) {
            isPhysicalNetworkLost = lost
            host.updateNotification()
        }
    }

    fun restart() {
        if (status.state.value == VpnState.RESTARTING) return
        val s = status.state.value
        if (s != VpnState.RUNNING && s != VpnState.STARTING) return

        Timber.d("Restarting VPN to apply new settings")
        restartJob?.cancel()
        restartJob = scope.launch(ioDispatcher) {
            tearDownForRestart()
            retryManager.reset()
            delay(RESTART_CLEANUP_DELAY_MS)
            start()
        }
    }

    fun start(startedFromBoot: Boolean = false) {
        val s = status.state.value
        if (s == VpnState.RUNNING || s == VpnState.STARTING) return
        status.state.value = VpnState.STARTING

        host.enterForeground()
        network.startNetworkMonitoring()

        startJob = scope.launch {
            try {
                val startupTime = clock()

                setPhase(host.getString(R.string.vpn_phase_loading_filters))
                host.updateNotification()

                val config = engine.prepareStartupConfig()
                firewallManager = config.firewallManager

                setPhase(host.getString(R.string.vpn_phase_preparing_dns))
                host.updateNotification()

                val httpsFilteringEnabled = appPrefs.getHttpsFilteringEnabledSnapshot()

                if (startedFromBoot && !network.isNetworkAvailable()) {
                    setPhase(host.getString(R.string.vpn_phase_waiting_network))
                    host.updateNotification()
                    Timber.d("Waiting for network before establishing VPN tunnel...")
                    network.networkAvailableFlow.first()
                    Timber.d("Network is now available, proceeding with VPN establishment")
                }

                setPhase(host.getString(R.string.vpn_phase_establishing))
                host.updateNotification()

                var vpnEstablished = false
                while (isActive && !vpnEstablished && retryManager.shouldRetry()) {
                    when (val tunnelRes = establishTunnel(config.whitelistedApps)) {
                        is TunnelResult.Success -> {
                            vpnInterface = tunnelRes.vpnInterface
                            resolvedWgConfigJson = tunnelRes.resolvedWgConfigJson
                            vpnEstablished = true
                            host.cancelWireGuardConfigIssue()
                        }
                        is TunnelResult.PermissionRevoked -> {
                            stop(showStoppedNotification = false)
                            host.showRevokedNotification()
                            return@launch
                        }
                        is TunnelResult.InvalidWireGuardConfig -> {
                            stop(showStoppedNotification = false)
                            host.showStartFailure(tunnelRes)
                            return@launch
                        }
                        is TunnelResult.Failure -> {
                            vpnEstablished = false
                        }
                    }

                    if (!vpnEstablished && retryManager.shouldRetry()) {
                        Timber.w("VPN establishment failed, retrying... (${retryManager.getRetryCount()}/${retryManager.getMaxRetries()})")
                        host.updateNotification()
                        retryManager.waitForRetry()
                    }
                }

                if (!vpnEstablished) {
                    Timber.e("Failed to establish VPN after ${retryManager.getMaxRetries()} attempts")
                    setPhase("")
                    stop()
                    return@launch
                }

                retryManager.reset()
                setPhase("")
                val resumedFromReconnect = isReconnecting && vpnStartTime > 0L
                isReconnecting = false
                status.state.value = VpnState.RUNNING
                appPrefs.setVpnEnabled(true)

                host.refreshPrivateDnsState()
                if (!resumedFromReconnect) {
                    vpnStartTime = clock()
                }
                status.startTimestamp = vpnStartTime

                val startupElapsed = clock() - startupTime
                Timber.d("VPN startup completed in ${startupElapsed}ms")

                val cachedTotal = dnsLogDao.getBlockedCountSync().toLong()
                allTimeBlockedCount.set(cachedTotal)
                val lastMilestone = appPrefs.lastMilestoneBlocked.first()
                nextMilestone = nextMilestoneThreshold(lastMilestone)

                host.updateNotification()
                Timber.d("VPN established successfully")

                host.broadcastWidgetUpdate()
                host.logBatteryStatus()
                network.startPeriodicMonitoring()

                engine.configure(config)
                engine.startFilterUpdateWatcher(this)

                vpnInterface?.let { pfd ->
                    engine.startTunnel(pfd, resolvedWgConfigJson, httpsFilteringEnabled)
                }

            } catch (e: CancellationException) {
                Timber.d("VPN startup cancelled")
            } catch (e: Exception) {
                Timber.e(e, "VPN startup failed")
                stop()
            }
        }
    }

    private var startJob: Job? = null
    private var restartJob: Job? = null

    fun stop(
        showStoppedNotification: Boolean = true,
        onStopped: (() -> Unit)? = null,
    ) {
        startJob?.cancel()
        startJob = null
        restartJob?.cancel()
        restartJob = null
        status.state.value = VpnState.STOPPING
        setPhase(host.getString(R.string.vpn_phase_closing_tunnel))
        isReconnecting = false
        isPhysicalNetworkLost = false
        network.cancelNetworkSwitch()
        status.startTimestamp = 0L

        host.updateNotification()
        network.stopNetworkMonitoring()
        network.stopPeriodicMonitoring()

        scope.launch(ioDispatcher) {
            appPrefs.setVpnEnabled(false)
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Timber.e("Error closing VPN interface: $e")
            }
            vpnInterface = null

            setPhase(host.getString(R.string.vpn_phase_stopping_engine))
            val goStop = scope.launch(NonCancellable + ioDispatcher) { engine.stop() }
            if (withTimeoutOrNull(GO_STOP_TIMEOUT_MS) { goStop.join() } == null) {
                Timber.w("Go tunnel stop still running after ${GO_STOP_TIMEOUT_MS}ms — finishing shutdown anyway")
            }

            withContext(mainDispatcher) {
                if (status.state.value != VpnState.STOPPING) {
                    Timber.w("Shutdown superseded by ${status.state.value} — leaving the new session alone")
                    return@withContext
                }
                setPhase(host.getString(R.string.vpn_phase_clearing_network))
                host.stopForeground(removeNotification = true)
                if (showStoppedNotification) {
                    host.showStoppedNotification()
                }
                onStopped?.invoke()
                host.stopSelf()
                Timber.d("VPN service stopSelf called, waiting for OS transport teardown")
            }

            host.scheduleStopFinalization {
                if (status.state.value == VpnState.STOPPING) {
                    status.state.value = VpnState.STOPPED
        setPhase("")
                    setPhase("")
                    status.lastStoppedTimestamp = clock()
                    status.privateDnsStrict.value = false
                    Timber.d("VPN fully stopped (OS transport cleared)")
                    host.onFullyStopped()
                }
            }
        }
    }

    /** Handles VPN revocation from the OS (another VPN app took over or user revoked in settings). */
    fun onRevoke(): Boolean {
        Timber.w("VPN revoked by system or another VPN app")
        host.showRevokedNotification()
        status.state.value = VpnState.STOPPED
        setPhase("")
        status.lastStoppedTimestamp = 0L
        status.privateDnsStrict.value = false
        isReconnecting = false
        isPhysicalNetworkLost = false
        network.cancelNetworkSwitch()
        status.startTimestamp = 0L

        network.stopNetworkMonitoring()
        network.stopPeriodicMonitoring()

        startJob?.cancel()
        startJob = null
        restartJob?.cancel()
        restartJob = null

        scope.launch(NonCancellable + ioDispatcher) {
            appPrefs.setVpnEnabled(false)
            appPrefs.setVpnRevokedByAnotherApp(true)
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Timber.e("Error closing VPN interface: $e")
            }
            vpnInterface = null

            val goStop = scope.launch(NonCancellable + ioDispatcher) { engine.stop() }
            if (withTimeoutOrNull(GO_STOP_TIMEOUT_MS) { goStop.join() } == null) {
                Timber.w("Go tunnel stop still running after ${GO_STOP_TIMEOUT_MS}ms — finishing shutdown anyway")
            }

            withContext(mainDispatcher) {
                host.stopForeground(removeNotification = true)
                host.stopSelf()
                host.onFullyStopped()
            }
        }
        return true
    }

    fun onDestroy() {
        if (status.state.value != VpnState.STOPPING) {
            status.state.value = VpnState.STOPPED
        setPhase("")
            status.lastStoppedTimestamp = clock()
        }
        status.privateDnsStrict.value = false
        isReconnecting = false
        isPhysicalNetworkLost = false
        status.startTimestamp = 0L

        network.stopNetworkMonitoring()
        network.stopPeriodicMonitoring()

        startJob?.cancel()
        startJob = null
        restartJob?.cancel()
        restartJob = null

        scope.cancel()
        try {
            vpnInterface?.close()
        } catch (_: Exception) { }
        vpnInterface = null
    }

    suspend fun tearDownForRestart() {
        status.state.value = VpnState.RESTARTING
        isReconnecting = true
        isPhysicalNetworkLost = false

        network.stopNetworkMonitoring()
        network.stopPeriodicMonitoring()
        engine.stop()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing VPN interface during network switch")
        }
        vpnInterface = null
    }
}
