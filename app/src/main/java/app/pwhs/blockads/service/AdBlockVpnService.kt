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
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.vpn.StartupConfig
import app.pwhs.blockads.service.vpn.VpnConnectionSupervisor
import app.pwhs.blockads.service.vpn.VpnEngineCoordinator
import app.pwhs.blockads.service.vpn.VpnNotificationManager
import app.pwhs.blockads.service.vpn.VpnSessionController
import app.pwhs.blockads.service.vpn.VpnSessionEngine
import app.pwhs.blockads.service.vpn.VpnSessionHost
import app.pwhs.blockads.service.vpn.VpnStatusStore
import app.pwhs.blockads.service.vpn.VpnTunnelBuilder
import app.pwhs.blockads.utils.AppNameResolver
import app.pwhs.blockads.utils.BatteryMonitor
import app.pwhs.blockads.utils.VpnUtils
import app.pwhs.blockads.utils.startOfDayMillis
import app.pwhs.blockads.widget.AdBlockWidgetProvider
import app.pwhs.blockads.worker.VpnResumeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.pwhs.blockads.START_VPN"
        const val ACTION_STOP = "app.pwhs.blockads.STOP_VPN"
        const val ACTION_PAUSE_1H = "app.pwhs.blockads.PAUSE_VPN_1H"
        const val ACTION_RESTART = "app.pwhs.blockads.RESTART_VPN"
        const val EXTRA_STARTED_FROM_BOOT = "extra_started_from_boot"

        internal val status = VpnStatusStore()
        val state: StateFlow<VpnState> = status.state.asStateFlow()

        val isRunning: Boolean get() = status.state.value == VpnState.RUNNING
        val isConnecting: Boolean get() = status.state.value == VpnState.STARTING
        val isRestarting: Boolean get() = status.state.value == VpnState.RESTARTING
        val isStopping: Boolean get() = status.state.value == VpnState.STOPPING

        var startTimestamp: Long
            get() = status.startTimestamp
            set(value) { status.startTimestamp = value }
        var lastStoppedTimestamp: Long
            get() = status.lastStoppedTimestamp
            set(value) { status.lastStoppedTimestamp = value }

        val privateDnsStrict: StateFlow<Boolean> = status.privateDnsStrict.asStateFlow()

        fun updatePrivateDnsState(linkProperties: android.net.LinkProperties?) {
            status.privateDnsStrict.value = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                linkProperties?.privateDnsServerName != null
        }

        fun requestRestart(context: Context) {
            val s = status.state.value
            if (s == VpnState.RUNNING || s == VpnState.STARTING) {
                context.startService(Intent(context, AdBlockVpnService::class.java).apply { action = ACTION_RESTART })
            }
        }

        fun start(context: Context) {
            context.startService(Intent(context, AdBlockVpnService::class.java).apply { action = ACTION_START })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AdBlockVpnService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var filterRepo: FilterListRepository
    private lateinit var appPrefs: AppPreferences
    private lateinit var dnsLogDao: DnsLogDao
    private lateinit var goTunnelAdapter: GoTunnelAdapter
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var vpnNotificationManager: VpnNotificationManager
    private lateinit var tunnelBuilder: VpnTunnelBuilder
    private lateinit var connectionSupervisor: VpnConnectionSupervisor
    private lateinit var engineCoordinator: VpnEngineCoordinator
    private lateinit var firewallRuleDao: FirewallRuleDao
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var session: VpnSessionController

    val connectingPhase: String get() = if (::session.isInitialized) session.connectingPhase else ""

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
            firewallManagerProvider = { session.firewallManager },
            recordLogProvider = { isRecordDnsLogsEnabled },
        )

        connectionSupervisor = VpnConnectionSupervisor(
            context = this,
            scope = serviceScope,
            appPrefs = appPrefs,
            batteryMonitor = batteryMonitor,
            isRunningProvider = { isRunning },
            isIdleProvider = { !isRunning && !isConnecting && !isRestarting && !isStopping },
            socketProtector = { fd -> protect(fd) },
            onTearDownForRestart = { session.tearDownForRestart() },
            onStartVpn = { session.startFromSupervisor() },
            onPhaseChanged = { phase -> session.connectingPhase = phase },
            onRefreshStats = {
                session.todayBlockedCount = dnsLogDao.getBlockedCountSinceSync(startOfDayMillis())
            },
            onUpdateNotification = { updateNotification() },
            onLinkPropertiesChanged = { linkProperties ->
                updatePrivateDnsState(linkProperties)
                serviceScope.launch {
                    engineCoordinator.handleLinkPropertiesChanged(goTunnelAdapter, linkProperties)
                }
            },
            onPhysicalNetworkLostChanged = { lost -> session.onPhysicalNetworkLostChanged(lost) },
            onNetworkActiveChanged = { network ->
                try {
                    setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
                    Timber.d("Updated underlying network: $network")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to set underlying network")
                }
            },
            onRequestRestart = { requestRestart(this@AdBlockVpnService) }
        )
        session = VpnSessionController(
            scope = serviceScope,
            status = status,
            host = sessionHost,
            engine = sessionEngine,
            network = connectionSupervisor,
            appPrefs = appPrefs,
            dnsLogDao = dnsLogDao,
            nextMilestoneThreshold = notificationHelper::nextMilestoneThreshold,
            establishTunnel = tunnelBuilder::establish,
        )
        connectionSupervisor.initializeNetworkMonitor()

        serviceScope.launch {
            appPrefs.recordDnsLogs.collect { enabled ->
                isRecordDnsLogsEnabled = enabled
                if (::goTunnelAdapter.isInitialized) goTunnelAdapter.setConnLogEnabled(enabled)
            }
        }
        serviceScope.launch {
            appPrefs.blockDohBypass.collect { enabled ->
                if (::goTunnelAdapter.isInitialized) goTunnelAdapter.setBlockDohBypass(enabled)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedFromBoot = intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) ?: false
        return when (intent?.action) {
            ACTION_STOP -> { session.stop(); START_NOT_STICKY }
            ACTION_PAUSE_1H -> { pauseVpn(); START_NOT_STICKY }
            ACTION_RESTART -> { session.restart(); START_STICKY }
            else -> { session.start(startedFromBoot); START_STICKY }
        }
    }

    private fun pauseVpn() {
        Timber.d("Pausing VPN for 1 hour")
        WorkManager.getInstance(this).enqueueUniqueWork(
            VpnResumeWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<VpnResumeWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        )
        session.stop(showStoppedNotification = false)
        vpnNotificationManager.showPausedNotification()
    }

    override fun onRevoke() {
        if (!session.onRevoke()) return
        super.onRevoke()
    }

    override fun onDestroy() {
        session.onDestroy()
        super.onDestroy()
    }

    private fun buildCurrentNotification(): Notification {
        return vpnNotificationManager.buildForegroundNotification(
            state = status.state.value,
            isConnecting = isConnecting,
            isReconnecting = session.isReconnecting,
            isStopping = isStopping,
            isRunning = isRunning,
            connectingPhase = session.connectingPhase,
            retryCount = session.retryManager.getRetryCount(),
            maxRetries = session.retryManager.getMaxRetries(),
            vpnStartTime = session.vpnStartTime,
            todayBlockedCount = session.todayBlockedCount,
            isPhysicalNetworkLost = session.isPhysicalNetworkLost
        )
    }

    private fun updateNotification() {
        val notification = buildCurrentNotification()
        vpnNotificationManager.updateNotification(notification)
        AdBlockWidgetProvider.sendUpdateBroadcast(this)
    }

    private val sessionHost = object : VpnSessionHost {
        override fun getString(resId: Int): String = this@AdBlockVpnService.getString(resId)

        override fun enterForeground() {
            vpnNotificationManager.createChannels()
            startForeground(VpnNotificationManager.NOTIFICATION_ID, buildCurrentNotification())
        }

        override fun updateNotification() = this@AdBlockVpnService.updateNotification()
        override fun showRevokedNotification() = vpnNotificationManager.showRevokedNotification()
        override fun showStoppedNotification() = vpnNotificationManager.showStoppedNotification()

        override fun stopForeground(removeNotification: Boolean) =
            this@AdBlockVpnService.stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)

        override fun stopSelf() = this@AdBlockVpnService.stopSelf()

        override fun refreshPrivateDnsState() {
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                updatePrivateDnsState(cm.activeNetwork?.let { cm.getLinkProperties(it) })
            }
        }

        override fun broadcastWidgetUpdate() = AdBlockWidgetProvider.sendUpdateBroadcast(this@AdBlockVpnService)
        override fun logBatteryStatus() = batteryMonitor.logBatteryStatus()

        override fun scheduleStopFinalization(onFinalized: () -> Unit) =
            VpnUtils.scheduleStopFinalization(applicationContext, onFinalized)

        override fun onFullyStopped() = AdBlockWidgetProvider.sendUpdateBroadcast(applicationContext)
    }

    private val sessionEngine = object : VpnSessionEngine {
        override suspend fun prepareStartupConfig(): StartupConfig = engineCoordinator.prepareStartupConfig()

        override suspend fun configure(config: StartupConfig) =
            engineCoordinator.configureEngine(goTunnelAdapter, config)

        override fun startFilterUpdateWatcher(scope: CoroutineScope) =
            engineCoordinator.startFilterUpdateWatcher(scope, goTunnelAdapter)

        override suspend fun startTunnel(
            vpnInterface: ParcelFileDescriptor,
            resolvedWgConfigJson: String,
            httpsFilteringEnabled: Boolean,
        ) = engineCoordinator.startTunnel(
            goTunnelAdapter = goTunnelAdapter,
            vpnInterface = vpnInterface,
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

        override fun stop() = goTunnelAdapter.stop()
    }

    fun protectSocket(fd: Int): Boolean {
        return protect(fd)
    }
}
