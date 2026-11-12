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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
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
        private const val NETWORK_STABILIZATION_DELAY_MS = 2000L
        private const val CONNECTION_PROBE_INTERVAL_MS = 60_000L
        const val ACTION_START = "app.pwhs.blockadstv.START_VPN"
        const val ACTION_STOP = "app.pwhs.blockadstv.STOP_VPN"

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
            if (_state.value == VpnState.RUNNING) {
                stop(context)
                val intent = Intent(context, TvVpnService::class.java).apply {
                    action = ACTION_START
                }
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var filterRepo: FilterListRepository
    private lateinit var tvPrefs: TvPreferences
    private lateinit var dnsLogDao: DnsLogDao
    private lateinit var firewallManager: TvFirewallManager
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var goTunnelAdapter: GoTunnelAdapter
    private lateinit var tunnelBuilder: TvVpnTunnelBuilder
    private lateinit var protectionHelper: TvProtectionTunnelHelper
    private val retryManager = VpnRetryManager()

    private var networkMonitor: NetworkMonitor? = null
    private var networkSwitchJob: Job? = null
    private var connectionProbeJob: Job? = null
    private var startJob: Job? = null

    @Volatile
    private var isRecordDnsLogsEnabled = true

    override fun onCreate() {
        super.onCreate()
        val koin = getKoin()
        filterRepo = koin.get()
        tvPrefs = koin.get()
        dnsLogDao = koin.get()
        firewallManager = koin.get()
        appNameResolver = koin.get()
        protectionHelper = koin.get()

        tunnelBuilder = TvVpnTunnelBuilder(this)
        goTunnelAdapter = GoTunnelAdapter(
            context = this,
            filterRepo = filterRepo,
            dnsLogDao = dnsLogDao,
            scope = serviceScope,
            appNameResolver = appNameResolver,
            firewallManager = firewallManager,
            recordLogProvider = { isRecordDnsLogsEnabled },
        )

        networkMonitor = NetworkMonitor(
            context = this,
            onNetworkAvailable = { handleNetworkAvailable() },
            onNetworkLost = { Timber.d("TV physical network lost") },
            onNetworkActiveChanged = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        setUnderlyingNetworks(null)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to reset underlying networks")
                    }
                }
            },
        )

        observePreferenceUpdates()
    }

    private fun observePreferenceUpdates() {
        serviceScope.launch {
            tvPrefs.recordDnsLogs.collect { enabled ->
                isRecordDnsLogsEnabled = enabled
                goTunnelAdapter.setConnLogEnabled(enabled)
            }
        }
        serviceScope.launch {
            tvPrefs.blockDohBypass.collect { enabled ->
                goTunnelAdapter.setBlockDohBypass(enabled)
            }
        }
        serviceScope.launch {
            tvPrefs.blockWebRtcLeak.collect { enabled ->
                goTunnelAdapter.setBlockWebRtc(enabled)
            }
        }
        serviceScope.launch {
            tvPrefs.preferredIPType.collect { ipType ->
                goTunnelAdapter.setPreferredIPType(ipType)
            }
        }
        serviceScope.launch {
            tvPrefs.filterHttp3.collect { enabled ->
                goTunnelAdapter.setFilterHttp3(enabled)
            }
        }
        protectionHelper.observeProtectionUpdates(
            scope = serviceScope,
            tvPrefs = tvPrefs,
            goTunnelAdapter = goTunnelAdapter,
            isRunning = { _state.value == VpnState.RUNNING },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> {
                startVpn()
                START_STICKY
            }
        }
    }

    private fun startVpn() {
        val s = _state.value
        if (s == VpnState.RUNNING || s == VpnState.STARTING) return
        _state.value = VpnState.STARTING

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting...", "Loading filters..."))
        networkMonitor?.startMonitoring()

        startJob = serviceScope.launch {
            try {
                val isFirstDownload = !tvPrefs.filtersDownloaded.first()
                if (isFirstDownload) {
                    filterRepo.forceReloadAllFilters()
                    tvPrefs.setFiltersDownloaded(true)
                } else {
                    filterRepo.seedDefaultsIfNeeded()
                }

                val domainCount = filterRepo.loadAllEnabledFilters().getOrDefault(0)
                firewallManager.loadRules()

                val dnsConfig = protectionHelper.resolveEffectiveDnsConfig(tvPrefs)
                val dnsResponseType = tvPrefs.dnsResponseType.first()
                val whitelistedApps = tvPrefs.getWhitelistedAppsSnapshot()
                val routingMode = tvPrefs.getRoutingModeSnapshot()
                val excludeLan = tvPrefs.excludeLan.first()
                val preferredIpType = tvPrefs.preferredIPType.first()
                val blockDohBypass = tvPrefs.blockDohBypass.first()
                val blockWebRtc = tvPrefs.blockWebRtcLeak.first()
                val filterHttp3 = tvPrefs.filterHttp3.first()

                val isProxyVpn = routingMode == TvPreferences.ROUTING_MODE_PROXY_VPN
                val activeProxy = if (isProxyVpn) tvPrefs.getActiveProxyProfileSnapshot() else null
                val proxyProtocol = activeProxy?.protocol?.value ?: ""
                val proxyConfigJson = activeProxy?.toEngineJson() ?: ""

                retryManager.reset()
                var establishedPfd: ParcelFileDescriptor? = null
                while (isActive && establishedPfd == null && retryManager.shouldRetry()) {
                    establishedPfd = tunnelBuilder.establish(
                        whitelistedApps = whitelistedApps,
                        isProxyVpn = isProxyVpn,
                        excludeLan = excludeLan,
                        preferredIpType = preferredIpType,
                        proxyHost = activeProxy?.server,
                    )
                    if (establishedPfd == null && retryManager.shouldRetry()) {
                        Timber.w(
                            "TV VPN establish failed, retrying (${retryManager.getRetryCount()}/${retryManager.getMaxRetries()})"
                        )
                        retryManager.waitForRetry()
                    }
                }

                if (establishedPfd == null) {
                    Timber.e("Failed to establish TV VPN after ${retryManager.getMaxRetries()} attempts")
                    stopVpn()
                    return@launch
                }

                vpnInterface = establishedPfd
                retryManager.reset()
                _state.value = VpnState.RUNNING
                tvPrefs.setVpnEnabled(true)
                startTimestamp = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NotificationManager::class.java)
                    val title = if (isProxyVpn) "BlockAds TV Proxy Active" else "BlockAds TV Active"
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification(title, "$domainCount filter rules loaded"),
                    )
                }

                goTunnelAdapter.configureDns(
                    protocol = dnsConfig.protocol,
                    primary = dnsConfig.primary,
                    fallback = dnsConfig.fallback,
                    dohUrl = dnsConfig.dohUrl,
                    odohRelayUrl = dnsConfig.odohRelayUrl,
                )
                goTunnelAdapter.setBlockResponseType(dnsResponseType)
                goTunnelAdapter.setPreferredIPType(preferredIpType)
                protectionHelper.applyInitialProtection(goTunnelAdapter)

                startDynamicEngineWatchers()
                startConnectionProbing()

                goTunnelAdapter.start(
                    vpnInterface = establishedPfd,
                    socketProtector = { fd ->
                        try { protect(fd) } catch (_: Exception) { false }
                    },
                    proxyProtocol = proxyProtocol,
                    proxyConfigJson = proxyConfigJson,
                    filterHttp3 = filterHttp3,
                    blockDohBypass = blockDohBypass,
                    blockWebRtc = blockWebRtc,
                )
            } catch (e: CancellationException) {
                Timber.d("TV VPN startup cancelled")
            } catch (e: Exception) {
                Timber.e(e, "VPN startup failed")
                stopVpn()
            }
        }
    }

    private fun startDynamicEngineWatchers() {
        serviceScope.launch {
            filterRepo.domainCountFlow.drop(1).collectLatest { count ->
                Timber.d("Filter count changed to $count, hot-reloading tries...")
                goTunnelAdapter.updateTries()
            }
        }
        serviceScope.launch {
            combine(
                tvPrefs.dnsProtocol,
                tvPrefs.upstreamDns,
                tvPrefs.fallbackDns,
                tvPrefs.dohUrl,
                tvPrefs.odohRelayUrl,
            ) { protocol, primary, fallback, doh, relay ->
                listOf(protocol.name, primary, fallback, doh, relay)
            }.drop(1).collectLatest {
                protectionHelper.reloadEffectiveDns(tvPrefs, goTunnelAdapter)
            }
        }
        serviceScope.launch {
            tvPrefs.dnsResponseType.drop(1).collectLatest { responseType ->
                goTunnelAdapter.setBlockResponseType(responseType)
            }
        }
    }

    private fun handleNetworkAvailable() {
        networkSwitchJob?.cancel()
        networkSwitchJob = serviceScope.launch {
            if (_state.value == VpnState.RUNNING) {
                protectionHelper.reloadEffectiveDns(tvPrefs, goTunnelAdapter)
                return@launch
            }
            val autoReconnect = tvPrefs.autoReconnect.first()
            val vpnWasEnabled = tvPrefs.vpnEnabled.first()
            if (autoReconnect && vpnWasEnabled && _state.value == VpnState.STOPPED) {
                Timber.d("Auto-reconnecting TV VPN after network became available")
                delay(NETWORK_STABILIZATION_DELAY_MS)
                if (_state.value == VpnState.STOPPED) {
                    startVpn()
                }
            }
        }
    }

    private fun startConnectionProbing() {
        connectionProbeJob?.cancel()
        val probe = ConnectionQualityProbe(
            socketProtector = { socket ->
                try { protect(socket) } catch (_: Exception) { false }
            },
            isEngineRunning = { goTunnelAdapter.isEngineRunning() },
        )
        connectionProbeJob = serviceScope.launch {
            var consecutiveStalls = 0
            while (isActive && _state.value == VpnState.RUNNING) {
                delay(CONNECTION_PROBE_INTERVAL_MS)
                if (_state.value != VpnState.RUNNING) break
                val result = probe.runDiagnosis()
                Timber.d(
                    "ConnectionQualityProbe: ${result.status} " +
                        "(physical=${result.physicalOk}, engine=${result.engineOk}, latency=${result.latencyMs}ms)"
                )
                when (result.status) {
                    ConnectionStatus.VPN_TUNNEL_STALLED -> {
                        consecutiveStalls++
                        if (consecutiveStalls >= 2) {
                            Timber.w("TV VPN tunnel stalled with healthy internet — restarting")
                            consecutiveStalls = 0
                            requestRestart(this@TvVpnService)
                        }
                    }
                    ConnectionStatus.HEALTHY, ConnectionStatus.NO_PHYSICAL_INTERNET -> {
                        consecutiveStalls = 0
                    }
                }
            }
        }
    }

    private fun stopVpn() {
        startJob?.cancel()
        startJob = null
        networkSwitchJob?.cancel()
        networkSwitchJob = null
        connectionProbeJob?.cancel()
        connectionProbeJob = null
        networkMonitor?.stopMonitoring()

        _state.value = VpnState.STOPPING
        startTimestamp = 0L

        serviceScope.launch(Dispatchers.IO) {
            goTunnelAdapter.stop()
            tvPrefs.setVpnEnabled(false)

            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null

            withContext(Dispatchers.Main) {
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
        networkMonitor?.stopMonitoring()
        connectionProbeJob?.cancel()
        networkSwitchJob?.cancel()
        serviceScope.cancel()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BlockAds TV VPN",
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
