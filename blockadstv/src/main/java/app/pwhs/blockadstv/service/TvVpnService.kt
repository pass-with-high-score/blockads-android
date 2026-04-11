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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var filterRepo: FilterListRepository
    private lateinit var tvPrefs: TvPreferences
    private lateinit var dnsLogDao: DnsLogDao
    private lateinit var goTunnelAdapter: GoTunnelAdapter
    private lateinit var appNameResolver: AppNameResolver

    override fun onCreate() {
        super.onCreate()
        val koin = org.koin.java.KoinJavaComponent.getKoin()
        filterRepo = koin.get()
        tvPrefs = koin.get()
        dnsLogDao = koin.get()

        appNameResolver = AppNameResolver(this)
        goTunnelAdapter = GoTunnelAdapter(
            context = this,
            filterRepo = filterRepo,
            dnsLogDao = dnsLogDao,
            scope = serviceScope,
            appNameResolver = appNameResolver,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
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

        serviceScope.launch {
            try {
                // Phase 1: Load filters
                Timber.d("Phase 1: Seeding default filters...")
                filterRepo.seedDefaultsIfNeeded()

                Timber.d("Phase 1: Loading enabled filters...")
                val result = filterRepo.loadAllEnabledFilters()
                val domainCount = result.getOrDefault(0)
                Timber.d("Filters loaded: $domainCount domains")
                Timber.d("Ad trie paths: '${filterRepo.getAdTriePath()}'")
                Timber.d("Security trie paths: '${filterRepo.getSecurityTriePath()}'")
                Timber.d("Ad bloom paths: '${filterRepo.getAdBloomPath()}'")
                Timber.d("Security bloom paths: '${filterRepo.getSecurityBloomPath()}'")

                // Phase 2: Read preferences
                Timber.d("Phase 2: Reading preferences...")
                val upstreamDns = tvPrefs.upstreamDns.first()
                val fallbackDns = tvPrefs.fallbackDns.first()
                val dnsProtocol = tvPrefs.dnsProtocol.first()
                val dohUrl = tvPrefs.dohUrl.first()
                val dnsResponseType = tvPrefs.dnsResponseType.first()
                val whitelistedApps = tvPrefs.getWhitelistedAppsSnapshot()
                Timber.d("DNS: protocol=$dnsProtocol, primary=$upstreamDns, fallback=$fallbackDns")
                Timber.d("Response type: $dnsResponseType, whitelisted apps: ${whitelistedApps.size}")

                // Phase 3: Establish VPN
                Timber.d("Phase 3: Establishing VPN tunnel...")
                val established = establishVpn(whitelistedApps)
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
                    nm.notify(
                        NOTIFICATION_ID,
                        buildNotification("BlockAds TV Active", "$domainCount filter rules loaded")
                    )
                }

                // Configure Go tunnel engine
                goTunnelAdapter.configureDns(
                    protocol = dnsProtocol.name,
                    primary = upstreamDns,
                    fallback = fallbackDns,
                    dohUrl = dohUrl,
                )
                goTunnelAdapter.setBlockResponseType(dnsResponseType)
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

                vpnInterface?.let {
                    goTunnelAdapter.start(
                        vpnInterface = it,
                        socketProtector = { fd ->
                            try { protect(fd) } catch (e: Exception) { false }
                        },
                    )
                }

            } catch (e: Exception) {
                Timber.e(e, "VPN startup failed")
                stopVpn()
            }
        }
    }

    private fun establishVpn(whitelistedApps: Set<String>): Boolean {
        if (VpnService.prepare(this) != null) {
            Timber.e("VPN not prepared or permission revoked")
            return false
        }

        return try {
            val builder = Builder()
                .setSession("BlockAds TV")
                .addAddress("10.0.0.2", 32)
                .addRoute("10.0.0.1", 32)
                .addDnsServer("10.0.0.1")
                .addAddress("fd00::2", 128)
                .addRoute("fd00::1", 128)
                .addDnsServer("fd00::1")
                .setBlocking(true)
                .setMtu(1500)

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
            }

            vpnInterface = builder.establish()
            Timber.d("VPN interface established: ${vpnInterface != null}")
            vpnInterface != null
        } catch (e: Exception) {
            Timber.e(e, "Error establishing VPN")
            false
        }
    }

    private fun stopVpn() {
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
