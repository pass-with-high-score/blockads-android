package app.pwhs.blockadstv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import app.pwhs.blockadstv.MainActivity
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.dao.DnsLogDao
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.utils.AppNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
import timber.log.Timber

/**
 * Foreground service for TV Root/Proxy mode.
 * Uses iptables to redirect outbound DNS traffic (port 53) to the local Go engine
 * at 127.0.0.1:15353, without taking over the Android VPN slot.
 */
class RootProxyService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 20
        private const val CHANNEL_ID = "blockadstv_root_proxy_channel"

        const val ACTION_START = "app.pwhs.blockadstv.ROOT_START"
        const val ACTION_STOP = "app.pwhs.blockadstv.ROOT_STOP"
        const val ACTION_RESTART = "app.pwhs.blockadstv.ROOT_RESTART"

        private val _state = MutableStateFlow(VpnState.STOPPED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

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

        fun requestRestart(context: Context) {
            val s = _state.value
            if (s == VpnState.RUNNING) {
                val intent = Intent(context, RootProxyService::class.java).apply {
                    action = ACTION_RESTART
                }
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var tvPrefs: TvPreferences
    private lateinit var filterRepo: FilterListRepository
    private lateinit var dnsLogDao: DnsLogDao
    private lateinit var firewallManager: TvFirewallManager
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var goTunnelAdapter: GoTunnelAdapter

    @Volatile
    private var whitelistedUids: List<Int> = emptyList()

    override fun onCreate() {
        super.onCreate()
        val koin = getKoin()
        tvPrefs = koin.get()
        filterRepo = koin.get()
        dnsLogDao = koin.get()
        firewallManager = koin.get()

        appNameResolver = AppNameResolver(this)
        goTunnelAdapter = GoTunnelAdapter(
            context = this,
            filterRepo = filterRepo,
            dnsLogDao = dnsLogDao,
            scope = serviceScope,
            appNameResolver = appNameResolver,
            firewallManager = firewallManager,
        )
        Timber.d("RootProxyService created on TV")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartProxy()
                return START_STICKY
            }
            else -> {
                startProxy()
                return START_STICKY
            }
        }
    }

    private fun startProxy() {
        if (_state.value == VpnState.RUNNING || _state.value == VpnState.STARTING) {
            Timber.d("RootProxyService already running/starting")
            return
        }

        _state.value = VpnState.STARTING
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting Root Proxy...", "Verifying root access..."))

        serviceScope.launch {
            try {
                // Ensure root shell is available
                val rootOk = IptablesManager.ensureRootShell() && IptablesManager.isRootAvailable()
                if (!rootOk) {
                    Timber.e("Root access not granted for RootProxyService")
                    stopProxy()
                    return@launch
                }

                // 1. Load filters
                filterRepo.seedDefaultsIfNeeded()
                val result = filterRepo.loadAllEnabledFilters()
                val domainCount = result.getOrDefault(0)

                // 2. Configure DNS engine
                val protocol = tvPrefs.dnsProtocol.first().name
                val primary = tvPrefs.upstreamDns.first()
                val fallback = tvPrefs.fallbackDns.first()
                val dohUrl = tvPrefs.dohUrl.first()
                val odohRelayUrl = tvPrefs.odohRelayUrl.first()
                val responseType = tvPrefs.dnsResponseType.first()

                goTunnelAdapter.configureDns(protocol, primary, fallback, dohUrl, odohRelayUrl)
                goTunnelAdapter.setBlockResponseType(responseType)

                // 3. Resolve whitelisted apps to UIDs
                whitelistedUids = tvPrefs.getWhitelistedAppsSnapshot().mapNotNull { pkg ->
                    try {
                        packageManager.getApplicationInfo(pkg, 0).uid
                    } catch (e: Exception) {
                        null
                    }
                }.distinct()

                // 4. Start standalone Go engine on port 15353
                val started = goTunnelAdapter.startStandalone(port = 15353)
                if (!started) {
                    Timber.e("Failed to start Go engine in standalone mode")
                    stopProxy()
                    return@launch
                }

                // 5. Apply iptables rules
                val iptablesOk = IptablesManager.setupRules(
                    context = this@RootProxyService,
                    whitelistUids = whitelistedUids
                )
                if (!iptablesOk) {
                    Timber.e("Failed to apply iptables rules")
                    stopProxy()
                    return@launch
                }

                _state.value = VpnState.RUNNING
                startTimestamp = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification("BlockAds TV Root Proxy Active", "$domainCount filter rules loaded")
                    )
                }

                // Hot-reloading listeners
                launch {
                    filterRepo.domainCountFlow.drop(1).collectLatest {
                        goTunnelAdapter.updateTries()
                    }
                }

                launch {
                    combine(
                        tvPrefs.dnsProtocol,
                        tvPrefs.upstreamDns,
                        tvPrefs.fallbackDns,
                        tvPrefs.dohUrl,
                        tvPrefs.odohRelayUrl,
                    ) { p, u, f, d, o ->
                        listOf(p.name, u, f, d, o)
                    }.drop(1).collectLatest { cfg ->
                        goTunnelAdapter.configureDns(cfg[0], cfg[1], cfg[2], cfg[3], cfg[4])
                    }
                }

                launch {
                    tvPrefs.dnsResponseType.drop(1).collectLatest { resp ->
                        goTunnelAdapter.setBlockResponseType(resp)
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Error starting TV Root Proxy")
                stopProxy()
            }
        }
    }

    private fun restartProxy() {
        serviceScope.launch {
            IptablesManager.teardownRules()
            goTunnelAdapter.stop()
            kotlinx.coroutines.delay(300)
            startProxy()
        }
    }

    private fun stopProxy() {
        _state.value = VpnState.STOPPING
        startTimestamp = 0L

        serviceScope.launch(Dispatchers.IO) {
            IptablesManager.teardownRules()
            goTunnelAdapter.stop()

            withContext(Dispatchers.Main) {
                _state.value = VpnState.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Timber.d("RootProxyService stopped on TV")
            }
        }
    }

    override fun onDestroy() {
        _state.value = VpnState.STOPPED
        startTimestamp = 0L
        IptablesManager.teardownRules()
        goTunnelAdapter.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BlockAds TV Root Proxy",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Root Proxy mode notification"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, RootProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopPendingIntent).build()
            )
            .build()
    }
}
