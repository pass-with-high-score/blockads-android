package app.pwhs.blockads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsErrorDao
import app.pwhs.blockads.data.DnsErrorEntry
import app.pwhs.blockads.data.DnsLogEntry
import app.pwhs.blockads.data.FilterListRepository
import app.pwhs.blockads.util.BatteryMonitor
import app.pwhs.blockads.util.AppNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpnService"
        private const val NOTIFICATION_ID = 1
        private const val REVOKED_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "blockads_vpn_channel"
        private const val ALERT_CHANNEL_ID = "blockads_vpn_alert_channel"
        private const val NETWORK_STABILIZATION_DELAY_MS = 2000L
        private const val MAX_PACKET_SIZE = 32767 // Maximum DNS packet size per RFC 1035
        const val ACTION_START = "app.pwhs.blockads.START_VPN"
        const val ACTION_STOP = "app.pwhs.blockads.STOP_VPN"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isConnecting = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var filterRepo: FilterListRepository
    private lateinit var appPrefs: AppPreferences
    private lateinit var dnsLogDao: app.pwhs.blockads.data.DnsLogDao
    private lateinit var dnsErrorDao: DnsErrorDao
    private var networkMonitor: NetworkMonitor? = null
    private val retryManager = VpnRetryManager(maxRetries = 5, initialDelayMs = 1000L, maxDelayMs = 60000L)
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var appNameResolver: AppNameResolver
    private var batteryMonitoringJob: kotlinx.coroutines.Job? = null
    private var notificationUpdateJob: kotlinx.coroutines.Job? = null

    private val totalQueries = AtomicLong(0)
    private val blockedQueries = AtomicLong(0)
    private var vpnStartTime: Long = 0L

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
        batteryMonitor = BatteryMonitor(this)
        appNameResolver = AppNameResolver(this)
        
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
            else -> {
                startVpn()
                return START_STICKY
            }
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
                val result = filterRepo.loadAllEnabledFilters()
                Log.d(TAG, "Filters loaded: ${result.getOrDefault(0)} unique domains")

                // Get upstream DNS, fallback DNS, and whitelisted apps
                val upstreamDns = appPrefs.upstreamDns.first()
                val fallbackDns = appPrefs.fallbackDns.first()
                val whitelistedApps = appPrefs.getWhitelistedAppsSnapshot()

                // Try to establish VPN with retry logic
                var vpnEstablished = false
                while (!vpnEstablished && retryManager.shouldRetry()) {
                    vpnEstablished = establishVpn(upstreamDns, whitelistedApps)
                    
                    if (!vpnEstablished && retryManager.shouldRetry()) {
                        Log.w(TAG, "VPN establishment failed, retrying... (${retryManager.getRetryCount()}/${retryManager.getMaxRetries()})")
                        updateNotification()
                        retryManager.waitForRetry()
                    }
                }

                if (!vpnEstablished) {
                    Log.e(TAG, "Failed to establish VPN after ${retryManager.getMaxRetries()} attempts")
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
                updateNotification() // Update to normal notification
                Log.d(TAG, "VPN established successfully")

                // Log initial battery state
                batteryMonitor.logBatteryStatus()
                
                // Start periodic battery monitoring
                startBatteryMonitoring()

                // Start periodic notification updates with stats
                startNotificationUpdates()

                // Start processing packets
                processPackets(upstreamDns, fallbackDns)

            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
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
                Log.w(TAG, "Could not exclude self from VPN", e)
            }

            // Exclude whitelisted apps from VPN
            for (appPackage in whitelistedApps) {
                try {
                    builder.addDisallowedApplication(appPackage)
                    Log.d(TAG, "Excluded from VPN: $appPackage")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not exclude $appPackage from VPN", e)
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN", e)
            false
        }
    }

    private fun processPackets(upstreamDns: String, fallbackDns: String) {
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
                    handleDnsQuery(query, outputStream, upstreamDns, fallbackDns)
                }
                // Non-DNS packets are silently dropped (they'll go through the normal
                // network stack since we only handle DNS via VPN routing)
            }
        } catch (e: Exception) {
            if (isProcessing) {
                Log.e(TAG, "Packet processing error", e)
            }
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            try { outputStream.close() } catch (_: Exception) {}
        }
    }

    private fun handleDnsQuery(
        query: DnsPacketParser.DnsQuery,
        outputStream: FileOutputStream,
        upstreamDns: String,
        fallbackDns: String
    ) {
        val domain = query.domain.lowercase()
        val startTime = System.currentTimeMillis()
        val appName = appNameResolver.resolve(query.sourcePort, query.sourceIp, query.destIp, query.destPort)

        if (filterRepo.isBlocked(domain)) {
            // Build and write blocked response (0.0.0.0)
            val response = DnsPacketParser.buildBlockedResponse(query)
            try {
                outputStream.write(response)
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing blocked response", e)
            }

            val elapsed = System.currentTimeMillis() - startTime
            logDnsQuery(domain, true, query.queryType, elapsed, appName)
            Log.d(TAG, "BLOCKED: $domain (app: $appName)")
            totalQueries.incrementAndGet()
            blockedQueries.incrementAndGet()
            logDnsQuery(domain, true, query.queryType, elapsed)
            Log.d(TAG, "BLOCKED: $domain")
        } else {
            // Forward to upstream DNS
            forwardDnsQuery(query, outputStream, upstreamDns, fallbackDns)

            val elapsed = System.currentTimeMillis() - startTime
            logDnsQuery(domain, false, query.queryType, elapsed, appName)
            totalQueries.incrementAndGet()
            logDnsQuery(domain, false, query.queryType, elapsed)
        }
    }

    private fun forwardDnsQuery(
        query: DnsPacketParser.DnsQuery,
        outputStream: FileOutputStream,
        upstreamDns: String,
        fallbackDns: String
    ) {
        // Try primary DNS server first
        var success = tryDnsQuery(query, outputStream, upstreamDns, false)
        
        // If primary fails and fallback is different, try fallback
        if (!success && fallbackDns != upstreamDns) {
            Log.w(TAG, "Primary DNS ($upstreamDns) failed for ${query.domain}, trying fallback ($fallbackDns)")
            success = tryDnsQuery(query, outputStream, fallbackDns, true)
        }
        
        // If both failed, return SERVFAIL
        if (!success) {
            Log.e(TAG, "Both primary and fallback DNS failed for ${query.domain}, returning SERVFAIL")
            try {
                val servfailResponse = DnsPacketParser.buildServfailResponse(query)
                outputStream.write(servfailResponse)
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing SERVFAIL response", e)
            }
        }
    }

    private fun tryDnsQuery(
        query: DnsPacketParser.DnsQuery,
        outputStream: FileOutputStream,
        dnsServer: String,
        isFallback: Boolean
    ): Boolean {
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

            // Build IP+UDP wrapper for the DNS response
            val dnsResponseData = responseBuffer.copyOf(responsePacket.length)
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
            Log.w(TAG, "DNS timeout for ${query.domain} on $dnsServer", e)
            logDnsError(query.domain, "TIMEOUT", e.message ?: "Socket timeout", dnsServer, isFallback)
            return false
        } catch (e: java.io.IOException) {
            Log.w(TAG, "DNS IO error for ${query.domain} on $dnsServer", e)
            logDnsError(query.domain, "IO_ERROR", e.message ?: "IO exception", dnsServer, isFallback)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "DNS error for ${query.domain} on $dnsServer", e)
            logDnsError(query.domain, "UNKNOWN", e.message ?: "Unknown error", dnsServer, isFallback)
            return false
        } finally {
            socket?.close()
        }
    }

    private fun logDnsError(domain: String, errorType: String, errorMessage: String, dnsServer: String, attemptedFallback: Boolean) {
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
                Log.e(TAG, "Failed to log DNS error", e)
            }
        }
    }

    private fun logDnsQuery(domain: String, isBlocked: Boolean, queryType: Int, responseTimeMs: Long, appName: String = "") {
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
                        appName = appName
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log DNS query", e)
            }
        }
    }

    private fun stopVpn(showStoppedNotification: Boolean = true) {
        isProcessing = false
        isConnecting = false
        isRunning = false
        isReconnecting = false

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
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        if (showStoppedNotification) {
            stopForeground(STOP_FOREGROUND_DETACH)
            showStoppedNotification()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
        Log.d(TAG, "VPN stopped")
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system or user")
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
        
        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        
        // Stop battery monitoring
        stopBatteryMonitoring()

        // Stop notification updates
        stopNotificationUpdates()
        
        serviceScope.cancel()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
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
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
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
            .setSmallIcon(android.R.drawable.ic_lock_lock)
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
                val blocked = blockedQueries.get()
                val total = totalQueries.get()
                val uptimeStr = formatUptime(System.currentTimeMillis() - vpnStartTime)
                getString(R.string.vpn_notification_stats_text, blocked, total, uptimeStr)
            }
            else -> getString(R.string.vpn_notification_text)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPendingIntent
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
        Log.d(TAG, "Network available - checking VPN status")
        
        // Use serviceScope to avoid blocking the callback thread
        serviceScope.launch {
            val autoReconnect = appPrefs.autoReconnect.first()
            val vpnWasEnabled = appPrefs.vpnEnabled.first()
            
            // If VPN should be running but isn't, try to reconnect
            if (autoReconnect && vpnWasEnabled && !isRunning && !isConnecting && !isReconnecting) {
                Log.d(TAG, "Auto-reconnecting VPN after network became available")
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
        Log.d(TAG, "Network lost")
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
                    Log.e(TAG, "Error monitoring battery", e)
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
                    delay(30_000L) // Update every 30 seconds
                    if (isRunning) {
                        updateNotification()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating notification", e)
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
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
