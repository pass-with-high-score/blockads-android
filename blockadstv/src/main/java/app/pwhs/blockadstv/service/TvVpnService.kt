package app.pwhs.blockadstv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.pwhs.blockadstv.MainActivity
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.dao.DnsLogDao
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.utils.AppNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
}

class TvVpnService : VpnService() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "blockadstv_vpn_channel"
        private const val GO_STOP_TIMEOUT_MS = 2000L
        const val ACTION_START = "app.pwhs.blockadstv.START_VPN"
        const val ACTION_STOP = "app.pwhs.blockadstv.STOP_VPN"
        const val ACTION_RESTART = "app.pwhs.blockadstv.RESTART_VPN"

        private val _state = MutableStateFlow(VpnState.STOPPED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        val isRunning: Boolean get() = _state.value == VpnState.RUNNING
        val isConnecting: Boolean get() = _state.value == VpnState.STARTING

        @Volatile
        var startTimestamp = 0L
            private set

        fun start(context: Context) {
            val intent = Intent(context, TvVpnService::class.java).apply {
                action = ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TvVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun requestRestart(context: Context) {
            val s = _state.value
            if (s == VpnState.RUNNING || s == VpnState.STARTING) {
                val intent = Intent(context, TvVpnService::class.java).apply {
                    action = ACTION_RESTART
                }
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var startJob: Job? = null
    private var restartJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var filterRepo: FilterListRepository
    private lateinit var tvPrefs: TvPreferences
    private lateinit var dnsLogDao: DnsLogDao
    private lateinit var firewallManager: TvFirewallManager
    private lateinit var goTunnelAdapter: GoTunnelAdapter
    private lateinit var appNameResolver: AppNameResolver

    override fun onCreate() {
        super.onCreate()
        val koin = org.koin.java.KoinJavaComponent.getKoin()
        filterRepo = koin.get()
        tvPrefs = koin.get()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartVpn()
                return START_STICKY
            }
            else -> {
                startVpn()
                return START_STICKY
            }
        }
    }

    private fun startVpn() {
        val s = _state.value
        if (s == VpnState.RUNNING || s == VpnState.STARTING) return
        _state.value = VpnState.STARTING

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting...", "Loading filters..."))

        startJob = serviceScope.launch {
            try {
                // Phase 1: Load filters
                val isFirstDownload = !tvPrefs.filtersDownloaded.first()
                if (isFirstDownload) {
                    Timber.d("Phase 1: First VPN start — force downloading all filters...")
                    filterRepo.forceReloadAllFilters()
                    tvPrefs.setFiltersDownloaded(true)
                } else {
                    Timber.d("Phase 1: Seeding default filters...")
                    filterRepo.seedDefaultsIfNeeded()
                }

                Timber.d("Phase 1: Loading enabled filters...")
                val result = filterRepo.loadAllEnabledFilters()
                val domainCount = result.getOrDefault(0)
                Timber.d("Filters loaded: $domainCount domains")
                Timber.d("Ad trie paths: '${filterRepo.getAdTriePath()}'")
                Timber.d("Security trie paths: '${filterRepo.getSecurityTriePath()}'")
                Timber.d("Ad bloom paths: '${filterRepo.getAdBloomPath()}'")
                Timber.d("Security bloom paths: '${filterRepo.getSecurityBloomPath()}'")

                // Phase 2: Read preferences & firewall rules
                Timber.d("Phase 2: Reading preferences...")
                firewallManager.loadRules()
                val upstreamDns = tvPrefs.upstreamDns.first()
                val fallbackDns = tvPrefs.fallbackDns.first()
                val dnsProtocol = tvPrefs.dnsProtocol.first()
                val dohUrl = tvPrefs.dohUrl.first()
                val odohRelayUrl = tvPrefs.odohRelayUrl.first()
                val dnsResponseType = tvPrefs.dnsResponseType.first()
                val whitelistedApps = tvPrefs.getWhitelistedAppsSnapshot()
                val routingMode = tvPrefs.getRoutingModeSnapshot()
                val isProxyVpn = routingMode == TvPreferences.ROUTING_MODE_PROXY_VPN
                val activeProxy = if (isProxyVpn) tvPrefs.getActiveProxyProfileSnapshot() else null
                val proxyProtocol = activeProxy?.protocol?.value ?: ""
                val proxyConfigJson = activeProxy?.toEngineJson() ?: ""
                val initialSniRules = app.pwhs.blockadstv.data.model.SniProxyRuleCodec.fromJsonList(
                    tvPrefs.getSniProxyRulesJsonSnapshot()
                )
                val hasActiveSniProxy = initialSniRules.any { it.enabled && it.targetIp.isNotBlank() }
                val needsFullTunnel = isProxyVpn || hasActiveSniProxy

                Timber.d("DNS: protocol=$dnsProtocol, primary=$upstreamDns, fallback=$fallbackDns")
                Timber.d("Response type: $dnsResponseType, routingMode: $routingMode, fullTunnel: $needsFullTunnel, proxy: ${activeProxy?.displayName}")

                // Phase 3: Establish VPN
                Timber.d("Phase 3: Establishing VPN tunnel (isProxyVpn=$isProxyVpn, fullTunnel=$needsFullTunnel)...")
                val established = establishVpn(whitelistedApps, needsFullTunnel)
                if (!established) {
                    Timber.e("Failed to establish VPN")
                    stopVpn()
                    return@launch
                }

                _state.value = VpnState.RUNNING
                tvPrefs.setVpnEnabled(true)
                startTimestamp = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NotificationManager::class.java)
                    val appName = getString(R.string.app_name)
                    val title = if (isProxyVpn) "$appName Proxy Active" else "$appName Active"
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification(title, "$domainCount filter rules loaded")
                    )
                }

                // Configure Go tunnel engine
                goTunnelAdapter.configureDns(
                    protocol = dnsProtocol.name,
                    primary = upstreamDns,
                    fallback = fallbackDns,
                    dohUrl = dohUrl,
                    odohRelayUrl = odohRelayUrl,
                )
                goTunnelAdapter.setBlockResponseType(dnsResponseType)
                goTunnelAdapter.setSniProxyRules(
                    app.pwhs.blockadstv.data.model.SniProxyRuleCodec.toEngineJson(
                        initialSniRules,
                        packageManager
                    )
                )
                Timber.d("Go engine configured, starting tunnel...")

                // Dynamically update Go Engine tries whenever filters change
                // (e.g. user toggles filters in UI while VPN is running)
                launch {
                    filterRepo.domainCountFlow.drop(1).collectLatest { count ->
                        Timber.d("Filter count changed to $count, hot-reloading tries...")
                        goTunnelAdapter.updateTries()
                        Timber.d("Tries reloaded: ad='${filterRepo.getAdTriePath()}', sec='${filterRepo.getSecurityTriePath()}'")
                    }
                }

                // Dynamically update DNS config whenever DNS settings change
                launch {
                    combine(
                        tvPrefs.dnsProtocol,
                        tvPrefs.upstreamDns,
                        tvPrefs.fallbackDns,
                        tvPrefs.dohUrl,
                        tvPrefs.odohRelayUrl,
                    ) { protocol, primary, fallback, doh, relay ->
                        listOf(protocol.name, primary, fallback, doh, relay)
                    }.drop(1).collectLatest { config ->
                        Timber.d("DNS config changed, hot-updating Go tunnel DNS: $config")
                        goTunnelAdapter.configureDns(
                            protocol = config[0],
                            primary = config[1],
                            fallback = config[2],
                            dohUrl = config[3],
                            odohRelayUrl = config[4],
                        )
                    }
                }

                // Dynamically update DNS response type
                launch {
                    tvPrefs.dnsResponseType.drop(1).collectLatest { responseType ->
                        Timber.d("DNS response type changed to $responseType")
                        goTunnelAdapter.setBlockResponseType(responseType)
                    }
                }

                // Dynamically update SNI proxy rules
                launch {
                    var wasFullTunnel = needsFullTunnel
                    tvPrefs.sniProxyRulesJson.drop(1).collectLatest { raw ->
                        val parsed = app.pwhs.blockadstv.data.model.SniProxyRuleCodec.fromJsonList(raw)
                        val nowNeedsFull = isProxyVpn || parsed.any { it.enabled && it.targetIp.isNotBlank() }
                        if (nowNeedsFull != wasFullTunnel) {
                            wasFullTunnel = nowNeedsFull
                            Timber.d("SNI proxy full-tunnel requirement changed to $nowNeedsFull, restarting VPN")
                            requestRestart(this@TvVpnService)
                        } else {
                            goTunnelAdapter.setSniProxyRules(
                                app.pwhs.blockadstv.data.model.SniProxyRuleCodec.toEngineJson(
                                    parsed,
                                    packageManager
                                )
                            )
                        }
                    }
                }

                vpnInterface?.let {
                    goTunnelAdapter.start(
                        vpnInterface = it,
                        socketProtector = { fd ->
                            try { protect(fd) } catch (e: Exception) { false }
                        },
                        proxyProtocol = proxyProtocol,
                        proxyConfigJson = proxyConfigJson,
                        useFullTunnel = needsFullTunnel,
                    )
                }

            } catch (e: Exception) {
                Timber.e(e, "VPN startup failed")
                stopVpn()
            }
        }
    }

    private fun establishVpn(whitelistedApps: Set<String>, isProxyVpn: Boolean): Boolean {
        if (VpnService.prepare(this) != null) {
            Timber.e("VPN not prepared or permission revoked")
            return false
        }

        return try {
            val appName = getString(R.string.app_name)
            val builder = Builder()
                .setSession(if (isProxyVpn) "$appName Proxy" else appName)
                .addAddress("100.64.100.2", 32)
                .addAddress("fd00::2", 128)
                .addDnsServer("100.64.100.1")
                .addDnsServer("fd00::1")
                .setBlocking(false)
                .setMtu(1350)

            if (isProxyVpn) {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            } else {
                builder.addRoute("100.64.100.1", 32)
                builder.addRoute("fd00::1", 128)
            }

            // Exclude our own app
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Timber.w(e, "Could not exclude self from VPN")
            }

            for (appPackage in whitelistedApps) {
                try {
                    builder.addDisallowedApplication(appPackage)
                    Timber.d("Excluded from VPN: $appPackage")
                } catch (e: Exception) {
                    Timber.w(e, "Could not exclude $appPackage")
                }
            }

            builder.allowBypass()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setUnderlyingNetworks(null)
                // VPN networks are metered by default on Android 10+ —
                // inherit meteredness from the underlying network instead,
                // so "Wi-Fi/unmetered only" apps keep working (see #181).
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val fd = vpnInterface!!.fileDescriptor
                        val flags = android.system.Os.fcntlInt(fd, android.system.OsConstants.F_GETFL, 0)
                        android.system.Os.fcntlInt(
                            fd,
                            android.system.OsConstants.F_SETFL,
                            flags or android.system.OsConstants.O_NONBLOCK
                        )
                        Timber.d("TV TUN file descriptor set to O_NONBLOCK successfully")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to enforce O_NONBLOCK on TV TUN fd")
                    }
                }
            }
            Timber.d("VPN interface established: ${vpnInterface != null}")
            vpnInterface != null
        } catch (e: Exception) {
            Timber.e(e, "Error establishing VPN")
            false
        }
    }

    private fun restartVpn() {
        startJob?.cancel()
        startJob = null
        restartJob?.cancel()
        _state.value = VpnState.STOPPING
        startTimestamp = 0L

        restartJob = serviceScope.launch(Dispatchers.IO) {
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null

            val goStop = launch(NonCancellable + Dispatchers.IO) { goTunnelAdapter.stop() }
            if (withTimeoutOrNull(GO_STOP_TIMEOUT_MS) { goStop.join() } == null) {
                Timber.w("Go tunnel stop still running after ${GO_STOP_TIMEOUT_MS}ms during restart")
            }

            withContext(Dispatchers.Main) {
                if (_state.value != VpnState.STOPPING) return@withContext
                _state.value = VpnState.STOPPED
                startVpn()
            }
        }
    }

    private fun stopVpn() {
        startJob?.cancel()
        startJob = null
        restartJob?.cancel()
        restartJob = null
        _state.value = VpnState.STOPPING
        startTimestamp = 0L

        serviceScope.launch(Dispatchers.IO) {
            tvPrefs.setVpnEnabled(false)

            // Close TUN fd first so OS tears down routes immediately, even if Go stack drain takes longer.
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null

            val goStop = launch(NonCancellable + Dispatchers.IO) { goTunnelAdapter.stop() }
            if (withTimeoutOrNull(GO_STOP_TIMEOUT_MS) { goStop.join() } == null) {
                Timber.w("Go tunnel stop still running after ${GO_STOP_TIMEOUT_MS}ms — finishing shutdown anyway")
            }

            withContext(Dispatchers.Main) {
                if (_state.value != VpnState.STOPPING) return@withContext
                _state.value = VpnState.STOPPED
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Timber.d("VPN stopped")
            }
        }
    }

    override fun onRevoke() {
        Timber.w("VPN revoked by system")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        _state.value = VpnState.STOPPED
        startTimestamp = 0L
        serviceScope.cancel()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "${getString(R.string.app_name)} VPN",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "VPN service notification"
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

        val stopIntent = Intent(this, TvVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
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
