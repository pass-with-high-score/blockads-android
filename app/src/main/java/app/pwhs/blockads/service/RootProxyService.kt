package app.pwhs.blockads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.work.OneTimeWorkRequestBuilder
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import kotlinx.coroutines.flow.asStateFlow
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.utils.AppNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.Locale
import app.pwhs.blockads.utils.startOfDayMillis
import app.pwhs.blockads.worker.RootProxyResumeWorker
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin
import timber.log.Timber

/**
 * Foreground service for Root/Proxy mode.
 * Uses iptables to redirect all DNS traffic (port 53) to the local Go engine
 * at 127.0.0.1:15353, instead of using VpnService.
 *
 * Lifecycle:
 * - onCreate: Initialize Go engine + Koin dependencies
 * - onStartCommand(ACTION_START): Apply iptables rules + start watchdog
 * - onStartCommand(ACTION_STOP): Teardown iptables + stop engine
 * - onDestroy / onTaskRemoved: Teardown iptables (failsafe)
 */
class RootProxyService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 10
        private const val CHANNEL_ID = "blockads_root_proxy_channel"

        const val ACTION_START = "app.pwhs.blockads.ROOT_START"
        const val ACTION_STOP = "app.pwhs.blockads.ROOT_STOP"
        const val ACTION_RESTART = "app.pwhs.blockads.ROOT_RESTART"
        const val ACTION_PAUSE_1H = "app.pwhs.blockads.ROOT_PAUSE_1H"
        const val EXTRA_STARTED_FROM_BOOT = "extra_started_from_boot"

        private val _state = kotlinx.coroutines.flow.MutableStateFlow(VpnState.STOPPED)
        val state: kotlinx.coroutines.flow.StateFlow<VpnState> = _state.asStateFlow()

        val isRunning: Boolean get() = _state.value == VpnState.RUNNING

        @Volatile
        var startTimestamp: Long = 0L
            private set

        fun start(context: Context) {
            val intent = Intent(context, RootProxyService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RootProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Request a Root Proxy restart to apply new settings/filter changes.
         * Only restarts if the service is currently running.
         */
        fun requestRestart(context: Context) {
            val s = _state.value
            if (s == VpnState.RUNNING || s == VpnState.RESTARTING) {
                val intent = Intent(context, RootProxyService::class.java).apply {
                    action = ACTION_RESTART
                }
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private val retryManager = VpnRetryManager(maxRetries = 10, maxDelayMs = 60000L)

    @Volatile
    private var todayBlockedCount: Int = 0
    private lateinit var appPrefs: AppPreferences
    private lateinit var filterRepo: FilterListRepository
    private lateinit var dnsLogDao: DnsLogDao
    private lateinit var firewallRuleDao: FirewallRuleDao
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var goTunnelAdapter: GoTunnelAdapter
    @Volatile
    private var firewallManager: FirewallManager? = null

    override fun onCreate() {
        super.onCreate()
        val koin = getKoin()
        appPrefs = koin.get()
        filterRepo = koin.get()
        dnsLogDao = koin.get()
        firewallRuleDao = koin.get()
        appNameResolver = AppNameResolver(this)
        goTunnelAdapter = GoTunnelAdapter(
            context = this,
            filterRepo = filterRepo,
            dnsLogDao = dnsLogDao,
            scope = serviceScope,
            appNameResolver = appNameResolver,
            firewallManagerProvider = { firewallManager }
        )
        Timber.d("RootProxyService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedFromBoot = intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) ?: false

        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_1H -> {
                pauseProxy()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartProxy()
                return START_STICKY
            }
            else -> {
                startProxy(startedFromBoot)
                return START_STICKY
            }
        }
    }

    private fun startProxy(startedFromBoot: Boolean = false) {
        if (_state.value == VpnState.RUNNING || _state.value == VpnState.STARTING) {
            Timber.d("RootProxyService already running/starting")
            return
        }
        
        _state.value = VpnState.STARTING

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        serviceScope.launch {
            try {
                // 1. Load filters (same as VPN mode)
                filterRepo.loadWhitelist()
                filterRepo.loadCustomRules()
                filterRepo.seedDefaultsIfNeeded()
                filterRepo.fetchAndSyncRemoteFilterLists()
                val result = filterRepo.loadAllEnabledFilters()
                Timber.d("Filters loaded for Root Proxy mode: ${result.getOrDefault(0)} domains")

                // 2. Setup engine parameters
                val protocol = appPrefs.dnsProtocol.first().name
                val primary = appPrefs.upstreamDns.first()
                val fallback = appPrefs.fallbackDns.first()
                val dohUrl = appPrefs.dohUrl.first()
                val safeSearch = appPrefs.safeSearchEnabled.first()
                val youtubeSafe = appPrefs.youtubeRestrictedMode.first()
                val responseType = appPrefs.dnsResponseType.first()

                goTunnelAdapter.configureDns(protocol, primary, fallback, dohUrl)
                goTunnelAdapter.configureSafeSearch(safeSearch, youtubeSafe)
                goTunnelAdapter.setBlockResponseType(responseType)

                // Load firewall rules if enabled
                val firewallEnabled = appPrefs.firewallEnabled.first()
                if (firewallEnabled) {
                    val fwManager = FirewallManager(this@RootProxyService, firewallRuleDao)
                    fwManager.loadRules()
                    firewallManager = fwManager
                    Timber.d("Firewall enabled for Root Proxy, rules loaded")
                } else {
                    firewallManager = null
                }

                // 3. Retry loop for Standalone mode and IPTables setup
                // This is crucial on boot where Magisk `su` might take a few seconds to become available
                var proxyStarted = false
                while (!proxyStarted && retryManager.shouldRetry()) {
                    val engineStarted = goTunnelAdapter.startStandalone(port = 15353)
                    if (engineStarted) {
                        if (IptablesManager.setupRules(this@RootProxyService)) {
                            proxyStarted = true
                        } else {
                            goTunnelAdapter.stop() // stop engine if iptables fails
                        }
                    }

                    if (!proxyStarted && retryManager.shouldRetry()) {
                         Timber.w("Root Proxy establishment failed, retrying... (${retryManager.getRetryCount()}/${retryManager.getMaxRetries()})")
                         retryManager.waitForRetry()
                    }
                }

                if (!proxyStarted) {
                    Timber.e("Failed to start Root Proxy after ${retryManager.getMaxRetries()} attempts")
                    stopProxy()
                    return@launch
                }
                
                retryManager.reset()

                _state.value = VpnState.RUNNING
                startTimestamp = System.currentTimeMillis()
                Timber.d("Root Proxy mode active — DNS traffic redirected to :15353")

                updateNotification()
                startNotificationUpdates()

                // 4. Start watchdog
                startWatchdog()
            } catch (e: Exception) {
                Timber.e(e, "Failed to start Root Proxy mode")
                IptablesManager.teardownRules()
                stopSelf()
            }
        }
    }

    private fun stopProxy(showPausedNotification: Boolean = false) {
        Timber.d("Stopping Root Proxy mode")
        _state.value = VpnState.STOPPING
        watchdogJob?.cancel()
        stopNotificationUpdates()

        // Teardown iptables rules (critical — prevents internet loss)
        IptablesManager.teardownRules()

        // Stop Go engine
        goTunnelAdapter.stop()

        _state.value = VpnState.STOPPED
        startTimestamp = 0L
        if (showPausedNotification) {
            stopForeground(STOP_FOREGROUND_DETACH)
            showPausedNotification()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun pauseProxy() {
        Timber.d("Pausing Root Proxy for 1 hour")

        // Schedule resume after 1 hour
        val resumeWork = OneTimeWorkRequestBuilder<RootProxyResumeWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS)
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            RootProxyResumeWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            resumeWork
        )

        // Stop proxy and show paused notification
        stopProxy(showPausedNotification = true)
    }

    /**
     * Restart the Root Proxy service to apply new settings/filter changes
     * without requiring the user to manually stop and start.
     */
    private fun restartProxy() {
        if (_state.value == VpnState.RESTARTING) return
        val s = _state.value
        if (s != VpnState.RUNNING && s != VpnState.STARTING) return

        _state.value = VpnState.RESTARTING
        Timber.d("Restarting Root Proxy to apply new settings")

        watchdogJob?.cancel()
        stopNotificationUpdates()

        serviceScope.launch(Dispatchers.IO) {
            // Stop Go engine
            goTunnelAdapter.stop()

            // Teardown iptables
            IptablesManager.teardownRules()

            // Brief delay to let resources clean up
            delay(1000L)

            // Restart
            _state.value = VpnState.STOPPED
            startProxy()
        }
    }

    /**
     * Watchdog monitors Go engine health every 10 seconds.
     * If the engine is dead, teardown iptables to prevent internet loss.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive && _state.value == VpnState.RUNNING) {
                delay(10_000)
                // TODO: Check Go engine health
                // if (!goEngine.isRunning()) {
                //     Timber.w("Go engine died — tearing down iptables")
                //     IptablesManager.teardownRules()
                //     isRunning = false
                //     stopSelf()
                //     break
                // }

                // For now, check if iptables rules are still active
                if (!IptablesManager.isActive()) {
                    Timber.w("iptables rules disappeared — re-applying")
                    IptablesManager.setupRules(this@RootProxyService)
                }
            }
        }
    }

    override fun onDestroy() {
        Timber.d("RootProxyService onDestroy — teardown iptables")
        _state.value = VpnState.STOPPED
        startTimestamp = 0L
        watchdogJob?.cancel()
        stopNotificationUpdates()
        IptablesManager.teardownRules()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.d("RootProxyService onTaskRemoved — teardown iptables")
        IptablesManager.teardownRules()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Root Proxy Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Root Proxy ad blocker is active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, RootProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, RootProxyService::class.java).apply {
            action = ACTION_PAUSE_1H
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 2, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val text = if (isRunning) {
            val uptimeStr = formatUptime(System.currentTimeMillis() - startTimestamp)
            getString(R.string.vpn_notification_stats_today, todayBlockedCount, uptimeStr)
        } else {
            getString(R.string.root_proxy_notification_text)
        }

        return builder
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.vpn_notification_action_pause), pausePendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.vpn_notification_action_stop), stopPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }
    
    private fun showPausedNotification() {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(this, RootProxyService::class.java).apply {
            action = ACTION_START
        }
        val startPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 3, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 3, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(R.string.vpn_paused_title))
            .setContentText(getString(R.string.vpn_paused_text))
            .setSmallIcon(R.drawable.ic_shield_off)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.vpn_stopped_action_enable), startPendingIntent
                ).build()
            )
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun startNotificationUpdates() {
        notificationUpdateJob?.cancel()

        notificationUpdateJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            while (isActive && isRunning) {
                try {
                    todayBlockedCount = dnsLogDao.getBlockedCountSinceSync(startOfDayMillis())
                    delay(30_000L)
                    if (isRunning && powerManager.isInteractive) {
                        updateNotification()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating notification in RootProxyService")
                    break
                }
            }
        }
    }

    private fun stopNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun formatUptime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
