package app.pwhs.blockads.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.vpn.TunnelResult
import app.pwhs.blockads.service.vpn.VpnConnectionSupervisor
import app.pwhs.blockads.service.vpn.VpnEngineCoordinator
import app.pwhs.blockads.service.vpn.VpnNotificationManager
import app.pwhs.blockads.service.vpn.VpnTunnelBuilder
import app.pwhs.blockads.utils.AppNameResolver
import app.pwhs.blockads.utils.BatteryMonitor
import app.pwhs.blockads.utils.VpnUtils
import app.pwhs.blockads.utils.startOfDayMillis
import app.pwhs.blockads.widget.AdBlockWidgetProvider
import app.pwhs.blockads.worker.VpnResumeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AdBlockVpnService : VpnService() {

    companion object {
        private const val RESTART_CLEANUP_DELAY_MS = 1000L
        // How long stopVpn() waits for the native Go engine teardown before
        // completing the service shutdown regardless (see stopVpn, #232).
        private const val GO_STOP_TIMEOUT_MS = 300L
        const val ACTION_START = "app.pwhs.blockads.START_VPN"
        const val ACTION_STOP = "app.pwhs.blockads.STOP_VPN"
        const val ACTION_PAUSE_1H = "app.pwhs.blockads.PAUSE_VPN_1H"
        const val ACTION_RESTART = "app.pwhs.blockads.RESTART_VPN"
        const val EXTRA_STARTED_FROM_BOOT = "extra_started_from_boot"
        private const val REVOKE_GRACE_MS = 10_000L

        private val _state = MutableStateFlow(VpnState.STOPPED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        val isRunning: Boolean get() = _state.value == VpnState.RUNNING
        val isConnecting: Boolean get() = _state.value == VpnState.STARTING
        val isRestarting: Boolean get() = _state.value == VpnState.RESTARTING
        val isStopping: Boolean get() = _state.value == VpnState.STOPPING

        var startTimestamp = 0L
        var lastStoppedTimestamp = 0L

        private val _privateDnsStrict = MutableStateFlow(false)
        val privateDnsStrict: StateFlow<Boolean> = _privateDnsStrict.asStateFlow()

        fun updatePrivateDnsState(linkProperties: android.net.LinkProperties?) {
            _privateDnsStrict.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                linkProperties?.privateDnsServerName != null
            } else {
                false
            }
        }

        fun requestRestart(context: Context) {
            val s = _state.value
            if (s == VpnState.RUNNING || s == VpnState.STARTING) {
                val intent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = ACTION_RESTART
                }
                context.startService(intent)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var lastVpnEstablishedAt: Long = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var filterRepo: FilterListRepository
    private lateinit var appPrefs: AppPreferences
    private lateinit var dnsLogDao: DnsLogDao
    private lateinit var goTunnelAdapter: GoTunnelAdapter
    private val retryManager = VpnRetryManager(maxRetries = 5, maxDelayMs = 60000L)
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var vpnNotificationManager: VpnNotificationManager
    private lateinit var tunnelBuilder: VpnTunnelBuilder
    private lateinit var connectionSupervisor: VpnConnectionSupervisor
    private lateinit var engineCoordinator: VpnEngineCoordinator
    private var firewallManager: FirewallManager? = null
    private lateinit var firewallRuleDao: FirewallRuleDao
    private lateinit var appNameResolver: AppNameResolver

    @Volatile private var resolvedWgConfigJson: String = ""
    private var vpnStartTime: Long = 0L
    @Volatile private var todayBlockedCount: Int = 0
    private val allTimeBlockedCount = AtomicLong(0)
    @Volatile private var nextMilestoneThreshold: Long? = null
    @Volatile private var isReconnecting = false

    @Volatile
    var connectingPhase: String = ""
        private set

    @Volatile private var isRecordDnsLogsEnabled = true

    override fun onCreate() {
        super.onCreate()
        val koin = org.koin.java.KoinJavaComponent.getKoin()
        filterRepo = koin.get()
        appPrefs = koin.get()
        dnsLogDao = koin.get()
        firewallRuleDao = koin.get()

        appNameResolver = AppNameResolver(this)
        batteryMonitor = BatteryMonitor(this)
        notificationHelper = NotificationHelper(this, appPrefs)
        vpnNotificationManager = VpnNotificationManager(this)
        tunnelBuilder = VpnTunnelBuilder(this, appPrefs)
        engineCoordinator = VpnEngineCoordinator(this, appPrefs, filterRepo, firewallRuleDao)

        goTunnelAdapter = GoTunnelAdapter(
            context = this,
            filterRepo = filterRepo,
            dnsLogDao = dnsLogDao,
            scope = serviceScope,
            appNameResolver = appNameResolver,
            firewallManagerProvider = { firewallManager },
            recordLogProvider = { isRecordDnsLogsEnabled },
        )

        connectionSupervisor = VpnConnectionSupervisor(
            context = this,
            scope = serviceScope,
            appPrefs = appPrefs,
            batteryMonitor = batteryMonitor,
            isRunningProvider = { isRunning },
            isIdleProvider = { !isRunning && !isConnecting && !isRestarting && !isStopping },
            onTearDownForRestart = { tearDownForRestart() },
            onStartVpn = {
                retryManager.reset()
                startVpn()
                isReconnecting = false
            },
            onPhaseChanged = { phase -> connectingPhase = phase },
            onRefreshStats = {
                todayBlockedCount = dnsLogDao.getBlockedCountSinceSync(startOfDayMillis())
            },
            onUpdateNotification = { updateNotification() },
            onLinkPropertiesChanged = { linkProperties ->
                updatePrivateDnsState(linkProperties)
                serviceScope.launch {
                    engineCoordinator.handleLinkPropertiesChanged(goTunnelAdapter, linkProperties)
                }
            }
        )
        connectionSupervisor.initializeNetworkMonitor()

        serviceScope.launch {
            appPrefs.recordDnsLogs.collect { enabled ->
                isRecordDnsLogsEnabled = enabled
                if (::goTunnelAdapter.isInitialized) {
                    goTunnelAdapter.setConnLogEnabled(enabled)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedFromBoot = intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) ?: false

        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_1H -> {
                pauseVpn()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartVpn()
                return START_STICKY
            }
            else -> {
                startVpn(startedFromBoot)
                return START_STICKY
            }
        }
    }

    private fun restartVpn() {
        if (_state.value == VpnState.RESTARTING) return
        val s = _state.value
        if (s != VpnState.RUNNING && s != VpnState.STARTING) return

        Timber.d("Restarting VPN to apply new settings")
        serviceScope.launch(Dispatchers.IO) {
            tearDownForRestart()
            retryManager.reset()
            delay(RESTART_CLEANUP_DELAY_MS)
            startVpn()
        }
    }

    private fun startVpn(startedFromBoot: Boolean = false) {
        val s = _state.value
        if (s == VpnState.RUNNING || s == VpnState.STARTING) return
        _state.value = VpnState.STARTING

        vpnNotificationManager.createChannels()
        startForeground(VpnNotificationManager.NOTIFICATION_ID, buildCurrentNotification())
        connectionSupervisor.startNetworkMonitoring()

        serviceScope.launch {
            try {
                val startupTime = System.currentTimeMillis()

                connectingPhase = getString(R.string.vpn_phase_loading_filters)
                updateNotification()

                val config = engineCoordinator.prepareStartupConfig()
                firewallManager = config.firewallManager

                connectingPhase = getString(R.string.vpn_phase_preparing_dns)
                updateNotification()

                val httpsFilteringEnabled = appPrefs.getHttpsFilteringEnabledSnapshot()

                if (startedFromBoot && !connectionSupervisor.isNetworkAvailable()) {
                    connectingPhase = getString(R.string.vpn_phase_waiting_network)
                    updateNotification()
                    Timber.d("Waiting for network before establishing VPN tunnel...")
                    connectionSupervisor.networkAvailableFlow.first()
                    Timber.d("Network is now available, proceeding with VPN establishment")
                }

                connectingPhase = getString(R.string.vpn_phase_establishing)
                updateNotification()

                var vpnEstablished = false
                while (!vpnEstablished && retryManager.shouldRetry()) {
                    when (val tunnelRes = tunnelBuilder.establish(config.whitelistedApps)) {
                        is TunnelResult.Success -> {
                            vpnInterface = tunnelRes.vpnInterface
                            resolvedWgConfigJson = tunnelRes.resolvedWgConfigJson
                            lastVpnEstablishedAt = android.os.SystemClock.elapsedRealtime()
                            vpnEstablished = true
                        }
                        is TunnelResult.PermissionRevoked -> {
                            stopVpn(showStoppedNotification = false)
                            vpnNotificationManager.showRevokedNotification()
                            return@launch
                        }
                        is TunnelResult.Failure -> {
                            vpnEstablished = false
                        }
                    }

                    if (!vpnEstablished && retryManager.shouldRetry()) {
                        Timber.w("VPN establishment failed, retrying... (${retryManager.getRetryCount()}/${retryManager.getMaxRetries()})")
                        updateNotification()
                        retryManager.waitForRetry()
                    }
                }

                if (!vpnEstablished) {
                    Timber.e("Failed to establish VPN after ${retryManager.getMaxRetries()} attempts")
                    connectingPhase = ""
                    stopVpn()
                    return@launch
                }

                retryManager.reset()
                connectingPhase = ""
                val resumedFromReconnect = isReconnecting && vpnStartTime > 0L
                isReconnecting = false
                _state.value = VpnState.RUNNING
                appPrefs.setVpnEnabled(true)

                runCatching {
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    updatePrivateDnsState(cm.activeNetwork?.let { cm.getLinkProperties(it) })
                }
                if (!resumedFromReconnect) {
                    vpnStartTime = System.currentTimeMillis()
                }
                startTimestamp = vpnStartTime

                val startupElapsed = System.currentTimeMillis() - startupTime
                Timber.d("VPN startup completed in ${startupElapsed}ms")

                val cachedTotal = dnsLogDao.getBlockedCountSync().toLong()
                allTimeBlockedCount.set(cachedTotal)
                val lastMilestone = appPrefs.lastMilestoneBlocked.first()
                nextMilestoneThreshold = notificationHelper.nextMilestoneThreshold(lastMilestone)

                updateNotification()
                Timber.d("VPN established successfully")

                AdBlockWidgetProvider.sendUpdateBroadcast(this@AdBlockVpnService)
                batteryMonitor.logBatteryStatus()
                connectionSupervisor.startPeriodicMonitoring()

                engineCoordinator.configureEngine(goTunnelAdapter, config)
                engineCoordinator.startFilterUpdateWatcher(this, goTunnelAdapter)

                vpnInterface?.let { pfd ->
                    engineCoordinator.startTunnel(
                        goTunnelAdapter = goTunnelAdapter,
                        vpnInterface = pfd,
                        resolvedWgConfigJson = resolvedWgConfigJson,
                        httpsFilteringEnabled = httpsFilteringEnabled,
                        certDir = filesDir.absolutePath,
                        socketProtector = { fd ->
                            try {
                                protect(fd)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to protect socket $fd")
                                false
                            }
                        }
                    )
                }

            } catch (e: Exception) {
                Timber.e(e, "VPN startup failed")
                stopVpn()
            }
        }
    }

    private fun pauseVpn() {
        Timber.d("Pausing VPN for 1 hour")
        WorkManager.getInstance(this).enqueueUniqueWork(
            VpnResumeWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<VpnResumeWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        )
        stopVpn(showStoppedNotification = false)
        vpnNotificationManager.showPausedNotification()
    }

    private fun stopVpn(showStoppedNotification: Boolean = true) {
        _state.value = VpnState.STOPPING
        isReconnecting = false
        connectionSupervisor.cancelNetworkSwitch()
        startTimestamp = 0L

        updateNotification()
        connectionSupervisor.stopNetworkMonitoring()
        connectionSupervisor.stopPeriodicMonitoring()

        serviceScope.launch(Dispatchers.IO) {
            appPrefs.setVpnEnabled(false)
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Timber.e("Error closing VPN interface: $e")
            }
            vpnInterface = null

            val goStop = serviceScope.launch(NonCancellable) { goTunnelAdapter.stop() }
            if (withTimeoutOrNull(GO_STOP_TIMEOUT_MS) { goStop.join() } == null) {
                Timber.w("Go tunnel stop still running after ${GO_STOP_TIMEOUT_MS}ms — finishing shutdown anyway")
            }

            withContext(Dispatchers.Main) {
                if (_state.value != VpnState.STOPPING) {
                    Timber.w("Shutdown superseded by ${_state.value} — leaving the new session alone")
                    return@withContext
                }
                if (showStoppedNotification) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    vpnNotificationManager.showStoppedNotification()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
                Timber.d("VPN service stopSelf called, waiting for OS transport teardown")
            }

            VpnUtils.scheduleStopFinalization(applicationContext) {
                if (_state.value == VpnState.STOPPING) {
                    _state.value = VpnState.STOPPED
                    lastStoppedTimestamp = System.currentTimeMillis()
                    _privateDnsStrict.value = false
                    Timber.d("VPN fully stopped (OS transport cleared)")
                    AdBlockWidgetProvider.sendUpdateBroadcast(applicationContext)
                }
            }
        }
    }

    override fun onRevoke() {
        val sinceEstablish = android.os.SystemClock.elapsedRealtime() - lastVpnEstablishedAt
        if (sinceEstablish in 0 until REVOKE_GRACE_MS) {
            Timber.w("Ignoring stale onRevoke (${sinceEstablish}ms after establish — superseded session)")
            return
        }

        Timber.w("VPN revoked by system or user")
        serviceScope.launch(NonCancellable) {
            appPrefs.setVpnEnabled(false)
        }
        vpnNotificationManager.showRevokedNotification()
        stopVpn(showStoppedNotification = false)
        super.onRevoke()
    }

    override fun onDestroy() {
        if (_state.value != VpnState.STOPPING) {
            _state.value = VpnState.STOPPED
            lastStoppedTimestamp = System.currentTimeMillis()
        }
        isReconnecting = false
        startTimestamp = 0L

        connectionSupervisor.stopNetworkMonitoring()
        connectionSupervisor.stopPeriodicMonitoring()

        serviceScope.cancel()
        try {
            vpnInterface?.close()
        } catch (_: Exception) { }
        vpnInterface = null
        super.onDestroy()
    }

    private fun buildCurrentNotification(): Notification {
        return vpnNotificationManager.buildForegroundNotification(
            state = _state.value,
            isConnecting = isConnecting,
            isReconnecting = isReconnecting,
            isStopping = isStopping,
            isRunning = isRunning,
            connectingPhase = connectingPhase,
            retryCount = retryManager.getRetryCount(),
            maxRetries = retryManager.getMaxRetries(),
            vpnStartTime = vpnStartTime,
            todayBlockedCount = todayBlockedCount
        )
    }

    private fun updateNotification() {
        val notification = buildCurrentNotification()
        vpnNotificationManager.updateNotification(notification)
        AdBlockWidgetProvider.sendUpdateBroadcast(this)
    }

    private suspend fun tearDownForRestart() {
        _state.value = VpnState.RESTARTING
        isReconnecting = true

        connectionSupervisor.stopNetworkMonitoring()
        connectionSupervisor.stopPeriodicMonitoring()
        goTunnelAdapter.stop()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing VPN interface during network switch")
        }
        vpnInterface = null
    }

    fun protectSocket(fd: Int): Boolean {
        return protect(fd)
    }
}
