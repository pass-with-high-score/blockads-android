package app.pwhs.blockads.service.vpn

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.FirewallManager
import app.pwhs.blockads.service.VpnRetryManager
import app.pwhs.blockads.service.VpnState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
        private const val REVOKE_GRACE_MS = 10_000L
    }

    val retryManager = VpnRetryManager(maxRetries = 5, maxDelayMs = 60000L)

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var lastVpnEstablishedAt: Long = 0L
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
        scope.launch(ioDispatcher) {
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

        scope.launch {
            try {
                val startupTime = clock()

                connectingPhase = host.getString(R.string.vpn_phase_loading_filters)
                host.updateNotification()

                val config = engine.prepareStartupConfig()
                firewallManager = config.firewallManager

                connectingPhase = host.getString(R.string.vpn_phase_preparing_dns)
                host.updateNotification()

                val httpsFilteringEnabled = appPrefs.getHttpsFilteringEnabledSnapshot()

                if (startedFromBoot && !network.isNetworkAvailable()) {
                    connectingPhase = host.getString(R.string.vpn_phase_waiting_network)
                    host.updateNotification()
                    Timber.d("Waiting for network before establishing VPN tunnel...")
                    network.networkAvailableFlow.first()
                    Timber.d("Network is now available, proceeding with VPN establishment")
                }

                connectingPhase = host.getString(R.string.vpn_phase_establishing)
                host.updateNotification()

                var vpnEstablished = false
                while (!vpnEstablished && retryManager.shouldRetry()) {
                    when (val tunnelRes = establishTunnel(config.whitelistedApps)) {
                        is TunnelResult.Success -> {
                            vpnInterface = tunnelRes.vpnInterface
                            resolvedWgConfigJson = tunnelRes.resolvedWgConfigJson
                            lastVpnEstablishedAt = elapsedRealtime()
                            vpnEstablished = true
                        }
                        is TunnelResult.PermissionRevoked -> {
                            stop(showStoppedNotification = false)
                            host.showRevokedNotification()
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
                    connectingPhase = ""
                    stop()
                    return@launch
                }

                retryManager.reset()
                connectingPhase = ""
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

            } catch (e: Exception) {
                Timber.e(e, "VPN startup failed")
                stop()
            }
        }
    }

    fun stop(showStoppedNotification: Boolean = true) {
        status.state.value = VpnState.STOPPING
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

            val goStop = scope.launch(NonCancellable) { engine.stop() }
            if (withTimeoutOrNull(GO_STOP_TIMEOUT_MS) { goStop.join() } == null) {
                Timber.w("Go tunnel stop still running after ${GO_STOP_TIMEOUT_MS}ms — finishing shutdown anyway")
            }

            withContext(mainDispatcher) {
                if (status.state.value != VpnState.STOPPING) {
                    Timber.w("Shutdown superseded by ${status.state.value} — leaving the new session alone")
                    return@withContext
                }
                if (showStoppedNotification) {
                    host.stopForeground(removeNotification = false)
                    host.showStoppedNotification()
                } else {
                    host.stopForeground(removeNotification = true)
                }
                host.stopSelf()
                Timber.d("VPN service stopSelf called, waiting for OS transport teardown")
            }

            host.scheduleStopFinalization {
                if (status.state.value == VpnState.STOPPING) {
                    status.state.value = VpnState.STOPPED
                    status.lastStoppedTimestamp = clock()
                    status.privateDnsStrict.value = false
                    Timber.d("VPN fully stopped (OS transport cleared)")
                    host.onFullyStopped()
                }
            }
        }
    }

    /** Returns false when the revoke is ignored as a stale callback from a superseded session. */
    fun onRevoke(): Boolean {
        val sinceEstablish = elapsedRealtime() - lastVpnEstablishedAt
        if (sinceEstablish in 0 until REVOKE_GRACE_MS) {
            Timber.w("Ignoring stale onRevoke (${sinceEstablish}ms after establish — superseded session)")
            return false
        }

        Timber.w("VPN revoked by system or user")
        scope.launch(NonCancellable) {
            appPrefs.setVpnEnabled(false)
        }
        host.showRevokedNotification()
        stop(showStoppedNotification = false)
        return true
    }

    fun onDestroy() {
        if (status.state.value != VpnState.STOPPING) {
            status.state.value = VpnState.STOPPED
            status.lastStoppedTimestamp = clock()
        }
        isReconnecting = false
        isPhysicalNetworkLost = false
        status.startTimestamp = 0L

        network.stopNetworkMonitoring()
        network.stopPeriodicMonitoring()

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
