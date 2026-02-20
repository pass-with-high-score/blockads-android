package app.pwhs.blockads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsErrorDao
import app.pwhs.blockads.data.DnsErrorEntry
import app.pwhs.blockads.data.DnsLogEntry
import app.pwhs.blockads.data.FilterListRepository
import app.pwhs.blockads.data.FirewallRuleDao
import app.pwhs.blockads.util.BatteryMonitor
import app.pwhs.blockads.util.AppNameResolver
import app.pwhs.blockads.util.startOfDayMillis
import app.pwhs.blockads.worker.VpnResumeWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class AdBlockVpnService : VpnService() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val REVOKED_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "blockads_vpn_channel"
        private const val ALERT_CHANNEL_ID = "blockads_vpn_alert_channel"
        private const val FIREWALL_CHANNEL_ID = "blockads_firewall_channel"
        private const val NETWORK_STABILIZATION_DELAY_MS = 2000L
        private const val RESTART_CLEANUP_DELAY_MS = 1000L
        private const val MAX_PACKET_SIZE = 32767 // Maximum DNS packet size per RFC 1035
        const val ACTION_START = "app.pwhs.blockads.START_VPN"
        const val ACTION_STOP = "app.pwhs.blockads.STOP_VPN"
        const val ACTION_PAUSE_1H = "app.pwhs.blockads.PAUSE_VPN_1H"
        const val ACTION_RESTART = "app.pwhs.blockads.RESTART_VPN"
        private const val FIREWALL_NOTIFICATION_COOLDOWN_MS = 60_000L // 1 minute per app
        private const val FIREWALL_NOTIFICATION_ID_BASE = 1000

        /**
         * Request a VPN restart to apply new settings.
         * Only restarts if the VPN is currently running.
         */
        fun requestRestart(context: Context) {
            if (isRunning || isRestarting) {
                val intent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = ACTION_RESTART
                }
                context.startService(intent)
            }
        }

        @Volatile
        var isRestarting = false
            private set

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isConnecting = false
            private set

        @Volatile
        var startTimestamp = 0L
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var filterRepo: FilterListRepository
    private lateinit var appPrefs: AppPreferences
    private lateinit var dnsLogDao: app.pwhs.blockads.data.DnsLogDao
    private lateinit var dnsErrorDao: DnsErrorDao
    private lateinit var dohClient: app.pwhs.blockads.dns.DohClient
    private lateinit var dotClient: app.pwhs.blockads.dns.DotClient
    private var networkMonitor: NetworkMonitor? = null
    private val retryManager =
        VpnRetryManager(maxRetries = 5, initialDelayMs = 1000L, maxDelayMs = 60000L)
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var notificationHelper: NotificationHelper
    private var firewallManager: FirewallManager? = null
    private lateinit var firewallRuleDao: FirewallRuleDao
    private var batteryMonitoringJob: kotlinx.coroutines.Job? = null
    private var notificationUpdateJob: kotlinx.coroutines.Job? = null

    private val totalQueries = AtomicLong(0)
    private val blockedQueries = AtomicLong(0)
    private var vpnStartTime: Long = 0L

    @Volatile
    private var todayBlockedCount: Int = 0

    // Cached all-time blocked count for milestone checks (avoids DB queries on hot path)
    private val allTimeBlockedCount = AtomicLong(0)

    @Volatile
    private var nextMilestoneThreshold: Long? = null

    @Volatile
    private var isProcessing = false

    @Volatile
    private var isReconnecting = false

    override fun onCreate() {
        super.onCreate()
        val koin = org.koin.java.KoinJavaComponent.getKoin()
        filterRepo = koin.get()
        appPrefs = koin.get()
        dnsLogDao = koin.get()
        dnsErrorDao = koin.get()
        dohClient = koin.get()
        dotClient = koin.get()
        firewallRuleDao = koin.get()
        batteryMonitor = BatteryMonitor(this)
        appNameResolver = AppNameResolver(this)
        notificationHelper = NotificationHelper(this, appPrefs)

        // Initialize network monitor
        networkMonitor = NetworkMonitor(
            context = this,
            onNetworkAvailable = { onNetworkAvailable() },
            onNetworkLost = { onNetworkLost() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                startVpn()
                return START_STICKY
            }
        }
    }

    private fun restartVpn() {
        if (isRestarting) return
        if (!isRunning && !isConnecting) return

        isRestarting = true
        Timber.d("Restarting VPN to apply new settings")

        // Stop packet processing
        isProcessing = false
        isRunning = false
        isConnecting = false
        isReconnecting = true

        // Stop monitoring
        networkMonitor?.stopMonitoring()
        stopBatteryMonitoring()
        stopNotificationUpdates()

        // Close current VPN interface (causes IOException in blocking read, ending processPackets)
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing VPN interface during restart")
        }
        vpnInterface = null

        // Reset retry manager for fresh start
        retryManager.reset()

        // Brief delay to let old VPN resources (file descriptors, sockets) clean up
        serviceScope.launch {
            delay(RESTART_CLEANUP_DELAY_MS)
            isRestarting = false
            startVpn()
        }
    }

    private fun startVpn() {
        if (isRunning || isConnecting) return
        isConnecting = true

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Start network monitoring
        networkMonitor?.startMonitoring()

        serviceScope.launch {
            try {
                // Seed defaults and load all enabled filter lists
                filterRepo.seedDefaultsIfNeeded()
                filterRepo.loadWhitelist()
                filterRepo.loadCustomRules()
                val result = filterRepo.loadAllEnabledFilters()
                Timber.d("Filters loaded: ${result.getOrDefault(0)} unique domains")

                // Get upstream DNS, fallback DNS, DNS response type, and whitelisted apps
                val upstreamDns = appPrefs.upstreamDns.first()
                val fallbackDns = appPrefs.fallbackDns.first()
                val dnsResponseType = appPrefs.dnsResponseType.first()
                val whitelistedApps = appPrefs.getWhitelistedAppsSnapshot()
                val safeSearchEnabled = appPrefs.safeSearchEnabled.first()
                val youtubeRestrictedMode = appPrefs.youtubeRestrictedMode.first()
                val firewallEnabled = appPrefs.firewallEnabled.first()

                // Load firewall rules if enabled
                if (firewallEnabled) {
                    val fwManager = FirewallManager(this@AdBlockVpnService, firewallRuleDao)
                    fwManager.loadRules()
                    firewallManager = fwManager
                    Timber.d("Firewall enabled, rules loaded")
                } else {
                    firewallManager = null
                }

                // Periodically refresh firewall rules and enabled state while the VPN coroutine is running.
                // This ensures that changes made via the UI (add/update/delete rules or toggling the
                // firewall preference) take effect without requiring a VPN restart.
                launch {
                    var lastEnabled = firewallEnabled
                    while (true) {
                        try {
                            val currentEnabled = appPrefs.firewallEnabled.first()

                            if (currentEnabled) {
                                // If firewall has just been enabled or manager is missing, (re)create and load rules.
                                if (!lastEnabled || firewallManager == null) {
                                    val fwManager =
                                        FirewallManager(this@AdBlockVpnService, firewallRuleDao)
                                    fwManager.loadRules()
                                    firewallManager = fwManager
                                    Timber.d("Firewall enabled or re-enabled, rules loaded")
                                } else {
                                    // Firewall remains enabled: reload rules to pick up rule changes.
                                    try {
                                        firewallManager?.loadRules()
                                        Timber.d("Firewall rules reloaded")
                                    } catch (e: Exception) {
                                        Timber.e("Error reloading firewall rules: $e")
                                    }
                                }
                            } else if (lastEnabled) {
                                // Firewall has just been disabled via preference change.
                                firewallManager = null
                                Timber.d("Firewall disabled via preference change")
                            }

                            lastEnabled = currentEnabled
                        } catch (e: Exception) {
                            // Log and continue; do not cancel the whole VPN coroutine due to a transient error.
                            Timber.e(e, "Error while monitoring firewall preference")
                        }

                        // Poll at a modest interval to balance responsiveness and resource usage.
                        delay(5_000)
                    }
                }
                // Resolve SafeSearch IPs if enabled
                val safeSearchIpCache = mutableMapOf<String, ByteArray>()
                if (safeSearchEnabled) {
                    resolveSafeSearchIps(upstreamDns, safeSearchIpCache)
                }
                // Resolve YouTube Restricted Mode IP if enabled (and not already resolved by SafeSearch)
                if (youtubeRestrictedMode && SafeSearchManager.YOUTUBE_RESTRICT_DOMAIN !in safeSearchIpCache) {
                    resolveYoutubeRestrictIp(upstreamDns, safeSearchIpCache)
                }

                // Try to establish VPN with retry logic
                var vpnEstablished = false
                while (!vpnEstablished && retryManager.shouldRetry()) {
                    vpnEstablished = establishVpn(upstreamDns, whitelistedApps)

                    if (!vpnEstablished && retryManager.shouldRetry()) {
                        Timber
                            .w("VPN establishment failed, retrying... (${retryManager.getRetryCount()}/${retryManager.getMaxRetries()})")
                        updateNotification()
                        retryManager.waitForRetry()
                    }
                }

                if (!vpnEstablished) {
                    Timber
                        .e("Failed to establish VPN after ${retryManager.getMaxRetries()} attempts")
                    isConnecting = false
                    stopVpn()
                    return@launch
                }

                // VPN established successfully - reset retry counter
                retryManager.reset()
                isConnecting = false
                isRunning = true
                appPrefs.setVpnEnabled(true)
                totalQueries.set(0)
                blockedQueries.set(0)
                vpnStartTime = System.currentTimeMillis()
                startTimestamp = vpnStartTime

                // Initialize cached all-time blocked count for milestone checks
                val cachedTotal = dnsLogDao.getBlockedCountSync().toLong()
                allTimeBlockedCount.set(cachedTotal)
                val lastMilestone = appPrefs.lastMilestoneBlocked.first()
                nextMilestoneThreshold = notificationHelper.nextMilestoneThreshold(lastMilestone)

                updateNotification() // Update to normal notification
                Timber.d("VPN established successfully")

                // Log initial battery state
                batteryMonitor.logBatteryStatus()

                // Start periodic battery monitoring
                startBatteryMonitoring()

                // Start periodic notification updates with stats
                startNotificationUpdates()

                // Start processing packets
                processPackets(
                    upstreamDns,
                    fallbackDns,
                    dnsResponseType,
                    safeSearchEnabled,
                    youtubeRestrictedMode,
                    safeSearchIpCache
                )

            } catch (e: Exception) {
                Timber.e(e, "VPN startup failed")
                isConnecting = false
                stopVpn()
            }
        }
    }

    private fun establishVpn(upstreamDns: String, whitelistedApps: Set<String>): Boolean {
        return try {
            // Establish VPN — only route DNS traffic, NOT all traffic
            // We use a fake DNS server IP (10.0.0.1) and only route that IP
            // through the TUN. All other traffic uses the normal network.
            val builder = Builder()
                .setSession("BlockAds")
                .addAddress("10.0.0.2", 32)
                .addRoute("10.0.0.1", 32)    // Only route fake DNS IP through TUN
                .addDnsServer("10.0.0.1")     // System sends DNS queries here
                .addAddress("fd00::2", 128)   // IPv6 TUN address
                .addRoute("fd00::1", 128)     // Route IPv6 DNS through TUN
                .addDnsServer("fd00::1")       // IPv6 DNS server
                .setBlocking(true)
                .setMtu(1500)

            // Exclude our own app from VPN to avoid loops
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Timber.w(e, "Could not exclude self from VPN")
            }

            // Exclude whitelisted apps from VPN
            for (appPackage in whitelistedApps) {
                try {
                    builder.addDisallowedApplication(appPackage)
                    Timber.d("Excluded from VPN: $appPackage")
                } catch (e: Exception) {
                    Timber.w(e, "Could not exclude $appPackage from VPN")
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Timber.e("Failed to establish VPN interface")
                return false
            }

            true
        } catch (e: Exception) {
            Timber.e(e, "Error establishing VPN")
            false
        }
    }

    private fun processPackets(
        upstreamDns: String,
        fallbackDns: String,
        dnsResponseType: String,
        safeSearchEnabled: Boolean,
        youtubeRestrictedMode: Boolean,
        safeSearchIpCache: Map<String, ByteArray>
    ) {
        isProcessing = true
        val fd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)

        // Reusable buffer for packet reading - SAFE because processing is single-threaded
        // The inputStream.read() call blocks until a packet arrives, ensuring sequential
        // processing. Each packet is fully processed before the next read() call.
        val buffer = ByteArray(MAX_PACKET_SIZE)

        try {
            while (isProcessing && isRunning) {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                // Parse DNS query directly from the reusable buffer
                // No need to copy since parseIpPacket doesn't modify the buffer
                val query = DnsPacketParser.parseIpPacket(buffer, length)

                if (query != null) {
                    handleDnsQuery(
                        query,
                        outputStream,
                        upstreamDns,
                        fallbackDns,
                        dnsResponseType,
                        safeSearchEnabled,
                        youtubeRestrictedMode,
                        safeSearchIpCache
                    )
                }
                // Non-DNS packets are silently dropped (they'll go through the normal
                // network stack since we only handle DNS via VPN routing)
            }
        } catch (e: Exception) {
            if (isProcessing) {
                Timber.e(e, "Packet processing error")
            }
        } finally {
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
            try {
                outputStream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleDnsQuery(
        query: DnsPacketParser.DnsQuery,
        outputStream: FileOutputStream,
        upstreamDns: String,
        fallbackDns: String,
        dnsResponseType: String,
        safeSearchEnabled: Boolean,
        youtubeRestrictedMode: Boolean,
        safeSearchIpCache: Map<String, ByteArray>
    ) {
        val domain = query.domain.lowercase()
        val startTime = System.currentTimeMillis()

        // Resolve UID once to get both app name and package name (avoids duplicate UID lookup)
        val fwManager = firewallManager
        val identity = if (fwManager != null) {
            appNameResolver.resolveIdentity(
                query.sourcePort,
                query.sourceIp,
                query.destIp,
                query.destPort
            )
        } else {
            null
        }
        val appName = identity?.appName
            ?: appNameResolver.resolve(
                query.sourcePort,
                query.sourceIp,
                query.destIp,
                query.destPort
            )

        // Firewall: block DNS for apps with active firewall rules
        if (fwManager != null && identity != null) {
            val appPackage = identity.packageName
            if (fwManager.shouldBlock(appPackage)) {
                val response = DnsPacketParser.buildRefusedResponse(query)
                try {
                    outputStream.write(response)
                    outputStream.flush()
                } catch (e: Exception) {
                    Timber.e(e, "Error writing firewall blocked response")
                }
                val elapsed = System.currentTimeMillis() - startTime
                logDnsQuery(
                    domain,
                    true,
                    query.queryType,
                    elapsed,
                    appName,
                    blockedBy = FilterListRepository.BLOCK_REASON_FIREWALL
                )
                Timber.d("FIREWALL BLOCKED: $domain (app: $appName / $appPackage)")
                totalQueries.incrementAndGet()
                blockedQueries.incrementAndGet()
                sendFirewallNotification(appName, appPackage)
                return
            }
        }

        // SafeSearch enforcement: redirect supported search engines (only for A/AAAA queries)
        if (safeSearchEnabled && (query.queryType == 1 || query.queryType == 28)) {
            val result = SafeSearchManager.check(domain)
            when (result.action) {
                SafeSearchManager.SafeSearchResult.Action.REDIRECT -> {
                    val redirectDomain = result.redirectDomain
                    if (redirectDomain != null) {
                        val cachedIp = safeSearchIpCache[redirectDomain]
                        if (cachedIp != null) {
                            val response = DnsPacketParser.buildRedirectResponse(query, cachedIp)
                            try {
                                outputStream.write(response)
                                outputStream.flush()
                            } catch (e: Exception) {
                                Timber.e(e, "Error writing SafeSearch redirect response")
                            }
                            val elapsed = System.currentTimeMillis() - startTime
                            logDnsQuery(
                                domain,
                                false,
                                query.queryType,
                                elapsed,
                                appName,
                                resolvedIp = formatIp(cachedIp)
                            )
                            Timber
                                .d("SAFESEARCH: $domain → $redirectDomain (${formatIp(cachedIp)})")
                            totalQueries.incrementAndGet()
                            return
                        }
                    }
                    // If no cached IP or no redirect domain, fall through to normal resolution
                }

                SafeSearchManager.SafeSearchResult.Action.NONE -> { /* proceed normally */
                }
            }
        }

        // YouTube Restricted Mode: redirect YouTube domains to restrict.youtube.com (only for A/AAAA queries)
        if (youtubeRestrictedMode && (query.queryType == 1 || query.queryType == 28) && SafeSearchManager.isYoutubeDomain(
                domain
            )
        ) {
            val cachedIp = safeSearchIpCache[SafeSearchManager.YOUTUBE_RESTRICT_DOMAIN]
            if (cachedIp != null) {
                val response = DnsPacketParser.buildRedirectResponse(query, cachedIp)
                try {
                    outputStream.write(response)
                    outputStream.flush()
                } catch (e: Exception) {
                    Timber.e(e, "Error writing YouTube restricted redirect response")
                }
                val elapsed = System.currentTimeMillis() - startTime
                logDnsQuery(
                    domain,
                    false,
                    query.queryType,
                    elapsed,
                    appName,
                    resolvedIp = formatIp(cachedIp)
                )
                Timber.d(
                    "YOUTUBE RESTRICTED: $domain → ${SafeSearchManager.YOUTUBE_RESTRICT_DOMAIN} (${
                        formatIp(cachedIp)
                    })"
                )
                totalQueries.incrementAndGet()
                return
            }
            // If no cached IP, fall through to normal resolution
        }

        if (filterRepo.isBlocked(domain)) {
            val blockedBy = filterRepo.getBlockReason(domain)
            // Build and write blocked response based on configured response type
            val response = when (dnsResponseType) {
                AppPreferences.DNS_RESPONSE_NXDOMAIN ->
                    DnsPacketParser.buildNxdomainResponse(query)

                AppPreferences.DNS_RESPONSE_REFUSED ->
                    DnsPacketParser.buildRefusedResponse(query)

                else -> // DNS_RESPONSE_CUSTOM_IP (0.0.0.0)
                    DnsPacketParser.buildBlockedResponse(query)
            }
            try {
                outputStream.write(response)
                outputStream.flush()
            } catch (e: Exception) {
                Timber.e(e, "Error writing blocked response")
            }

            val elapsed = System.currentTimeMillis() - startTime
            logDnsQuery(domain, true, query.queryType, elapsed, appName, blockedBy = blockedBy)
            Timber.d("BLOCKED: $domain (app: $appName)")
            totalQueries.incrementAndGet()
            blockedQueries.incrementAndGet()

            // Check for milestone achievements using in-memory counter
            val currentTotal = allTimeBlockedCount.incrementAndGet()
            val threshold = nextMilestoneThreshold
            if (threshold != null && currentTotal >= threshold) {
                // Prevent redundant coroutine launches while check is in progress
                nextMilestoneThreshold = null
                serviceScope.launch {
                    try {
                        notificationHelper.checkAndNotifyMilestone(currentTotal)
                        // Update next threshold after check
                        val lastMilestone = appPrefs.lastMilestoneBlocked.first()
                        nextMilestoneThreshold =
                            notificationHelper.nextMilestoneThreshold(lastMilestone)
                    } catch (e: Exception) {
                        Timber.e(e, "Error checking milestone")
                        // Restore threshold so checks resume
                        nextMilestoneThreshold = threshold
                    }
                }
            }
        } else {
            // Forward to upstream DNS
            forwardDnsQuery(query, outputStream, upstreamDns, fallbackDns)

            val elapsed = System.currentTimeMillis() - startTime
            logDnsQuery(domain, false, query.queryType, elapsed, appName)
            totalQueries.incrementAndGet()
        }
    }

    private fun forwardDnsQuery(
        query: DnsPacketParser.DnsQuery,
        outputStream: FileOutputStream,
        upstreamDns: String,
        fallbackDns: String
    ) {
        // Note: Using runBlocking here is intentional and necessary. This method is called
        // from processPackets(), which is a blocking I/O loop reading from a FileInputStream.
        // The entire packet processing pipeline is synchronous by design (VPN TUN interface
        // requires blocking reads). Making this suspend would require converting the entire
        // packet processing loop to coroutines, which is not compatible with blocking I/O
        // on FileInputStream/FileOutputStream.
        val (dnsProtocol, dohUrl) = runBlocking {
            appPrefs.dnsProtocol.first() to appPrefs.dohUrl.first()
        }

        // Try primary DNS server first
        var success = tryDnsQuery(query, outputStream, upstreamDns, dohUrl, dnsProtocol, false)

        // If primary fails and fallback is different, try fallback (with PLAIN protocol as fallback)
        if (!success && fallbackDns != upstreamDns) {
            Timber
                .w("Primary DNS ($upstreamDns) failed for ${query.domain}, trying fallback ($fallbackDns) with PLAIN protocol")
            success = tryDnsQuery(
                query,
                outputStream,
                fallbackDns,
                dohUrl,
                app.pwhs.blockads.data.DnsProtocol.PLAIN,
                true
            )
        }

        // If both failed, return SERVFAIL
        if (!success) {
            Timber
                .e("Both primary and fallback DNS failed for ${query.domain}, returning SERVFAIL")
            try {
                val servfailResponse = DnsPacketParser.buildServfailResponse(query)
                outputStream.write(servfailResponse)
                outputStream.flush()
            } catch (e: Exception) {
                Timber.e(e, "Error writing SERVFAIL response")
            }
        }
    }

    private fun tryDnsQuery(
        query: DnsPacketParser.DnsQuery,
        outputStream: FileOutputStream,
        dnsServer: String,
        dohUrl: String,
        protocol: app.pwhs.blockads.data.DnsProtocol,
        isFallback: Boolean
    ): Boolean {
        try {
            // Get DNS response based on protocol
            // Note: Using runBlocking for suspend functions is necessary here because
            // we're in a blocking I/O context (processPackets loop). The VPN TUN interface
            // requires synchronous packet processing with FileInputStream/FileOutputStream.
            val dnsResponseData = when (protocol) {
                app.pwhs.blockads.data.DnsProtocol.DOH -> {
                    Timber.d("Using DoH for ${query.domain} to $dohUrl")
                    runBlocking { dohClient.query(dohUrl, query.rawDnsPayload) }
                }

                app.pwhs.blockads.data.DnsProtocol.DOT -> {
                    Timber.d("Using DoT for ${query.domain} to $dnsServer")
                    runBlocking { dotClient.query(dnsServer, query.rawDnsPayload) }
                }

                app.pwhs.blockads.data.DnsProtocol.PLAIN -> {
                    Timber.d("Using plain DNS for ${query.domain} to $dnsServer")
                    tryPlainDnsQuery(query, dnsServer)
                }
            }

            if (dnsResponseData == null) {
                Timber.w("DNS query failed for ${query.domain} using $protocol")
                logDnsError(
                    query.domain,
                    "QUERY_FAILED",
                    "Failed to get response using $protocol",
                    dnsServer,
                    isFallback
                )
                return false
            }

            // Build IP+UDP wrapper for the DNS response
            val fullResponse = DnsPacketParser.buildIpUdpPacket(
                sourceIp = query.destIp,
                destIp = query.sourceIp,
                sourcePort = query.destPort,
                destPort = query.sourcePort,
                payload = dnsResponseData
            )

            outputStream.write(fullResponse)
            outputStream.flush()
            return true

        } catch (e: java.net.SocketTimeoutException) {
            Timber.w(e, "DNS timeout for ${query.domain} on $dnsServer")
            logDnsError(
                query.domain,
                "TIMEOUT",
                e.message ?: "Socket timeout",
                dnsServer,
                isFallback
            )
            return false
        } catch (e: java.io.IOException) {
            Timber.w(e, "DNS IO error for ${query.domain} on $dnsServer")
            logDnsError(
                query.domain,
                "IO_ERROR",
                e.message ?: "IO exception",
                dnsServer,
                isFallback
            )
            return false
        } catch (e: Exception) {
            Timber.w(e, "DNS query failed for ${query.domain} on $dnsServer")
            logDnsError(
                query.domain,
                "QUERY_ERROR",
                e.message ?: "Unknown error",
                dnsServer,
                isFallback
            )
            return false
        }
    }

    private fun tryPlainDnsQuery(query: DnsPacketParser.DnsQuery, dnsServer: String): ByteArray? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket) // Prevent VPN loop

            val serverAddress = InetAddress.getByName(dnsServer)
            val requestPacket = DatagramPacket(
                query.rawDnsPayload,
                query.rawDnsPayload.size,
                serverAddress,
                53
            )
            socket.soTimeout = 5000 // 5 second timeout
            socket.send(requestPacket)

            // Receive response
            val responseBuffer = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            return responseBuffer.copyOf(responsePacket.length)

        } catch (e: Exception) {
            Timber.w("Plain DNS query failed: $e")
            return null
        } finally {
            socket?.close()
        }
    }

    private fun logDnsError(
        domain: String,
        errorType: String,
        errorMessage: String,
        dnsServer: String,
        attemptedFallback: Boolean
    ) {
        serviceScope.launch {
            try {
                dnsErrorDao.insert(
                    DnsErrorEntry(
                        domain = domain,
                        errorType = errorType,
                        errorMessage = errorMessage,
                        dnsServer = dnsServer,
                        attemptedFallback = attemptedFallback
                    )
                )
            } catch (e: Exception) {
                Timber.e("Failed to log DNS error: $e")
            }
        }
    }

    private fun logDnsQuery(
        domain: String,
        isBlocked: Boolean,
        queryType: Int,
        responseTimeMs: Long,
        appName: String = "",
        resolvedIp: String = "",
        blockedBy: String = ""
    ) {
        serviceScope.launch {
            try {
                val typeStr = when (queryType) {
                    1 -> "A"
                    28 -> "AAAA"
                    5 -> "CNAME"
                    else -> "OTHER"
                }
                dnsLogDao.insert(
                    DnsLogEntry(
                        domain = domain,
                        isBlocked = isBlocked,
                        queryType = typeStr,
                        responseTimeMs = responseTimeMs,
                        appName = appName,
                        resolvedIp = resolvedIp,
                        blockedBy = blockedBy
                    )
                )
            } catch (e: Exception) {
                Timber.e("Failed to log DNS query: $e")
            }
        }
    }

    /**
     * Resolve an A record for a domain via a protected UDP DNS query.
     * Returns the 4-byte IPv4 address, or null if resolution fails.
     */
    private fun resolveARecordViaUdp(upstreamDns: String, domain: String): ByteArray? {
        val transactionId = (System.nanoTime() and 0xFFFF).toInt()
        val queryPayload = DnsPacketParser.buildDnsQueryPayload(domain, 1, transactionId)

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)

            val serverAddress = InetAddress.getByName(upstreamDns)
            val requestPacket = DatagramPacket(
                queryPayload, queryPayload.size, serverAddress, 53
            )
            socket.soTimeout = 5000
            socket.send(requestPacket)

            val responseBuffer = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            val responseData = responseBuffer.copyOf(responsePacket.length)
            return DnsPacketParser.parseFirstARecord(responseData)
        } finally {
            socket?.close()
        }
    }

    /**
     * Resolve SafeSearch domain IPs at VPN startup using a protected socket.
     * Caches the resolved IPv4 addresses for use during DNS query handling.
     */
    private fun resolveSafeSearchIps(upstreamDns: String, cache: MutableMap<String, ByteArray>) {
        val safeSearchDomains = listOf(
            "forcesafesearch.google.com",
            "strict.bing.com"
        )
        for (domain in safeSearchDomains) {
            try {
                val ip = resolveARecordViaUdp(upstreamDns, domain)
                if (ip != null) {
                    cache[domain] = ip
                    Timber.d("SafeSearch resolved: $domain → ${formatIp(ip)}")
                } else {
                    Timber.w("SafeSearch: No A record for $domain")
                }
            } catch (e: Exception) {
                Timber.e("SafeSearch: Failed to resolve $domain: $e")
            }
        }
        Timber.e("SafeSearch IPs resolved: ${cache.size}/${safeSearchDomains.size}")
    }

    /**
     * Resolve restrict.youtube.com IP at VPN startup for YouTube Restricted Mode.
     */
    private fun resolveYoutubeRestrictIp(
        upstreamDns: String,
        cache: MutableMap<String, ByteArray>
    ) {
        val domain = SafeSearchManager.YOUTUBE_RESTRICT_DOMAIN
        try {
            val ip = resolveARecordViaUdp(upstreamDns, domain)
            if (ip != null) {
                cache[domain] = ip
                Timber.d("YouTube Restricted Mode resolved: $domain → ${formatIp(ip)}")
            } else {
                Timber.w("YouTube Restricted Mode: No A record for $domain")
            }
        } catch (e: Exception) {
            Timber.e(e, "YouTube Restricted Mode: Failed to resolve $domain")
        }
    }

    private fun formatIp(ip: ByteArray): String {
        return ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun pauseVpn() {
        Timber.d("Pausing VPN for 1 hour")

        // Schedule resume after 1 hour
        val resumeWork = OneTimeWorkRequestBuilder<VpnResumeWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            VpnResumeWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            resumeWork
        )

        // Stop VPN but show paused notification
        stopVpn(showStoppedNotification = false)
        showPausedNotification()
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

        val startIntent = Intent(this, AdBlockVpnService::class.java).apply {
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

    private fun stopVpn(showStoppedNotification: Boolean = true) {
        isProcessing = false
        isConnecting = false
        isRunning = false
        isReconnecting = false
        isRestarting = false
        startTimestamp = 0L

        // Stop network monitoring
        networkMonitor?.stopMonitoring()

        // Stop battery monitoring
        stopBatteryMonitoring()

        // Stop notification updates
        stopNotificationUpdates()

        runBlocking {
            appPrefs.setVpnEnabled(false)
        }

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Timber.e("Error closing VPN interface: $e")
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (showStoppedNotification) {
            stopForeground(STOP_FOREGROUND_DETACH)
            showStoppedNotification()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
        Timber.d("VPN stopped")
    }

    override fun onRevoke() {
        Timber.w("VPN revoked by system or user")
        // Update preferences to reflect VPN is no longer enabled
        // Use a non-cancellable context to ensure preference is updated
        serviceScope.launch(NonCancellable) {
            appPrefs.setVpnEnabled(false)
        }
        showRevokedNotification()
        stopVpn(showStoppedNotification = false)
        super.onRevoke()
    }

    override fun onDestroy() {
        isProcessing = false
        isConnecting = false
        isRunning = false
        isReconnecting = false
        isRestarting = false
        startTimestamp = 0L

        // Stop network monitoring
        networkMonitor?.stopMonitoring()

        // Stop battery monitoring
        stopBatteryMonitoring()

        // Stop notification updates
        stopNotificationUpdates()

        serviceScope.cancel()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.vpn_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.vpn_alert_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createFirewallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FIREWALL_CHANNEL_ID,
                getString(R.string.firewall_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.firewall_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // Rate-limit firewall notifications to avoid flooding
    private val lastFirewallNotificationTime = ConcurrentHashMap<String, Long>()
    private fun sendFirewallNotification(appName: String, packageName: String) {
        if (packageName.isEmpty()) return

        val now = System.currentTimeMillis()
        val lastTime = lastFirewallNotificationTime[packageName]
        if (lastTime != null && (now - lastTime) < FIREWALL_NOTIFICATION_COOLDOWN_MS) return
        lastFirewallNotificationTime[packageName] = now

        createFirewallNotificationChannel()

        val displayName = appName.ifEmpty { packageName }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, FIREWALL_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(R.string.firewall_notification_title))
            .setContentText(getString(R.string.firewall_notification_text, displayName))
            .setSmallIcon(R.drawable.ic_shield_on)
            .setAutoCancel(true)
            .build()

        val notificationId =
            FIREWALL_NOTIFICATION_ID_BASE + (packageName.hashCode() and 0x7FFFFFFF) % 500
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    private fun showRevokedNotification() {
        createAlertNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(R.string.vpn_revoked_title))
            .setContentText(getString(R.string.vpn_revoked_text))
            .setSmallIcon(R.drawable.ic_error)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(REVOKED_NOTIFICATION_ID, notification)
    }

    private fun showStoppedNotification() {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(this, AdBlockVpnService::class.java).apply {
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
            .setContentTitle(getString(R.string.vpn_stopped_title))
            .setContentText(getString(R.string.vpn_stopped_text))
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

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AdBlockVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, AdBlockVpnService::class.java).apply {
            action = ACTION_PAUSE_1H
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 4, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val title = when {
            isReconnecting -> getString(R.string.vpn_notification_reconnecting)
            retryManager.getRetryCount() > 0 -> getString(R.string.vpn_notification_retrying)
            else -> getString(R.string.vpn_notification_title)
        }

        val text = when {
            isReconnecting -> getString(R.string.vpn_notification_reconnecting_text)
            retryManager.getRetryCount() > 0 -> getString(
                R.string.vpn_notification_retry_text,
                retryManager.getRetryCount(),
                retryManager.getMaxRetries()
            )

            isRunning -> {
                val uptimeStr = formatUptime(System.currentTimeMillis() - vpnStartTime)
                val todayBlocked = todayBlockedCount
                getString(R.string.vpn_notification_stats_today, todayBlocked, uptimeStr)
            }

            else -> getString(R.string.vpn_notification_text)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
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
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun onNetworkAvailable() {
        Timber.d("Network available - checking VPN status")

        // Use serviceScope to avoid blocking the callback thread
        serviceScope.launch {
            val autoReconnect = appPrefs.autoReconnect.first()
            val vpnWasEnabled = appPrefs.vpnEnabled.first()

            // If VPN should be running but isn't, try to reconnect
            if (autoReconnect && vpnWasEnabled && !isRunning && !isConnecting && !isReconnecting) {
                Timber.d("Auto-reconnecting VPN after network became available")
                isReconnecting = true

                // Wait a bit for network to stabilize
                delay(NETWORK_STABILIZATION_DELAY_MS)

                if (!isRunning && !isConnecting) {
                    retryManager.reset()
                    startVpn()
                }
                isReconnecting = false
            }
        }
    }

    private fun onNetworkLost() {
        Timber.d("Network lost")
        // Note: We don't stop the VPN when network is lost, as it may come back
        // The VPN will automatically reconnect when network is available again
    }

    /**
     * Start periodic battery monitoring to track battery usage.
     * Logs battery status every 5 minutes while VPN is running.
     */
    private fun startBatteryMonitoring() {
        // Cancel any existing monitoring job
        batteryMonitoringJob?.cancel()

        batteryMonitoringJob = serviceScope.launch {
            while (isRunning) {
                try {
                    delay(5 * 60 * 1000L) // Wait 5 minutes
                    if (isRunning) {
                        batteryMonitor.logBatteryStatus()
                    }
                } catch (e: Exception) {
                    Timber.e("Error monitoring battery: $e")
                    break
                }
            }
        }
    }

    private fun stopBatteryMonitoring() {
        batteryMonitoringJob?.cancel()
        batteryMonitoringJob = null
    }

    /**
     * Start periodic notification updates to refresh stats display.
     * Updates the notification every 30 seconds while VPN is running.
     */
    private fun startNotificationUpdates() {
        notificationUpdateJob?.cancel()

        notificationUpdateJob = serviceScope.launch {
            while (isRunning) {
                try {
                    // Refresh today's blocked count from database
                    todayBlockedCount = dnsLogDao.getBlockedCountSinceSync(startOfDayMillis())

                    delay(30_000L) // Update every 30 seconds
                    if (isRunning) {
                        updateNotification()
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
