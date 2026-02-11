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
import app.pwhs.blockads.data.DnsLogEntry
import app.pwhs.blockads.data.FilterListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "blockads_vpn_channel"
        private const val NETWORK_STABILIZATION_DELAY_MS = 2000L
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
    private var networkMonitor: NetworkMonitor? = null
    private val retryManager = VpnRetryManager(maxRetries = 5, initialDelayMs = 1000L, maxDelayMs = 60000L)

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

                // Get upstream DNS and whitelisted apps
                val upstreamDns = appPrefs.upstreamDns.first()
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
                updateNotification() // Update to normal notification
                Log.d(TAG, "VPN established successfully")

                // Start processing packets
                processPackets(upstreamDns)

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

    private fun processPackets(upstreamDns: String) {
        isProcessing = true
        val fd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)
        val buffer = ByteArray(32767)

        try {
            while (isProcessing && isRunning) {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                // Copy packet data
                val packet = buffer.copyOf(length)

                // Try to parse DNS query
                val query = DnsPacketParser.parseIpPacket(packet, length)

                if (query != null) {
                    handleDnsQuery(query, outputStream, upstreamDns)
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
        upstreamDns: String
    ) {
        val domain = query.domain.lowercase()
        val startTime = System.currentTimeMillis()

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
            logDnsQuery(domain, true, query.queryType, elapsed)
            Log.d(TAG, "BLOCKED: $domain")
        } else {
            // Forward to upstream DNS
            forwardDnsQuery(query, outputStream, upstreamDns)

            val elapsed = System.currentTimeMillis() - startTime
            logDnsQuery(domain, false, query.queryType, elapsed)
        }
    }

    private fun forwardDnsQuery(
        query: DnsPacketParser.DnsQuery,
        outputStream: FileOutputStream,
        upstreamDns: String
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket) // Prevent VPN loop

            val dnsServer = InetAddress.getByName(upstreamDns)
            val requestPacket = DatagramPacket(
                query.rawDnsPayload,
                query.rawDnsPayload.size,
                dnsServer,
                53
            )
            socket.soTimeout = 5000
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

        } catch (e: Exception) {
            Log.e(TAG, "DNS forward error for ${query.domain}", e)
        } finally {
            socket?.close()
        }
    }

    private fun logDnsQuery(domain: String, isBlocked: Boolean, queryType: Int, responseTimeMs: Long) {
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
                        responseTimeMs = responseTimeMs
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log DNS query", e)
            }
        }
    }

    private fun stopVpn() {
        isProcessing = false
        isConnecting = false
        isRunning = false
        isReconnecting = false

        // Stop network monitoring
        networkMonitor?.stopMonitoring()

        runBlocking {
            appPrefs.setVpnEnabled(false)
        }

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "VPN stopped")
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system or user")
        // Update preferences to reflect VPN is no longer enabled
        runBlocking {
            appPrefs.setVpnEnabled(false)
        }
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        isProcessing = false
        isConnecting = false
        isRunning = false
        isReconnecting = false
        
        // Stop network monitoring
        networkMonitor?.stopMonitoring()
        
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
        val autoReconnect = runBlocking { appPrefs.autoReconnect.first() }
        val vpnWasEnabled = runBlocking { appPrefs.vpnEnabled.first() }
        
        // If VPN should be running but isn't, try to reconnect
        if (autoReconnect && vpnWasEnabled && !isRunning && !isConnecting && !isReconnecting) {
            Log.d(TAG, "Auto-reconnecting VPN after network became available")
            isReconnecting = true
            serviceScope.launch {
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
}
