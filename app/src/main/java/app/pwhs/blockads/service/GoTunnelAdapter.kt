package app.pwhs.blockads.service

import android.content.Context
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.utils.AppNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import tunnel.AppResolver
import tunnel.DomainChecker
import tunnel.FirewallChecker
import tunnel.SocketProtector

/**
 * Bridge between Android VpnService and the Go DNS tunnel engine.
 *
 * Responsibilities:
 * - Pass TUN file descriptor to Go engine
 * - Implement [DomainChecker] so Go calls Kotlin's mmap'd Trie for blocking decisions
 * - Implement [FirewallChecker] so Go calls Kotlin's FirewallManager for per-app blocking
 * - Implement [SocketProtector] so Go can protect sockets from VPN routing loop
 * - Receive DNS log events from Go and write to Room DB
 * - Pass WireGuard config JSON to Go engine on startup (unified pipeline)
 */
class GoTunnelAdapter(
    private val context: Context,
    private val filterRepo: FilterListRepository,
    private val dnsLogDao: DnsLogDao,
    private val scope: CoroutineScope,
    private val appNameResolver: AppNameResolver,
    /**
     * Returns the current [FirewallManager] if firewall is enabled, or null if disabled.
     * This is a lambda so it always reads the latest value from [AdBlockVpnService].
     */
    private val firewallManagerProvider: () -> FirewallManager?,
) {
    private val engine = tunnel.Tunnel.newEngine()

    @Volatile
    private var isRunning = false

    /**
     * Configure the DNS settings for the Go engine.
     */
    fun configureDns(
        protocol: String,
        primary: String,
        fallback: String,
        dohUrl: String,
    ) {
        engine.setDNS(protocol, primary, fallback, dohUrl)
    }

    /**
     * Configure the block response type.
     * @param responseType "CUSTOM_IP", "NXDOMAIN", or "REFUSED"
     */
    fun setBlockResponseType(responseType: String) {
        engine.setBlockResponseType(responseType)
    }

    /**
     * Configure split-DNS zones for WireGuard internal domains.
     * @param zones Comma-separated zone suffixes (e.g., "internal,local,lan")
     */
    fun setSplitDNSZones(zones: String) {
        engine.setSplitDNSZones(zones)
    }

    /**
     * Configure SafeSearch and YouTube restricted mode.
     */
    fun configureSafeSearch(safeSearchEnabled: Boolean, youtubeRestricted: Boolean) {
        engine.setSafeSearch(safeSearchEnabled)
        engine.setYouTubeRestricted(youtubeRestricted)
    }

    /**
     * Set up the domain checker (uses Kotlin's FilterListRepository).
     */
    private fun setupDomainChecker() {
        engine.setDomainChecker(object : DomainChecker {
            override fun isBlocked(domain: String): Boolean {
                return filterRepo.isBlocked(domain)
            }

            override fun getBlockReason(domain: String): String {
                return filterRepo.getBlockReason(domain)
            }

            override fun hasCustomRule(domain: String): Long {
                return filterRepo.hasCustomRule(domain)
            }
        })
    }

    /**
     * Set up the app resolver to get the AppName for every DNS query (used for logging).
     * Uses [AppNameResolver] to map source port → UID → app name.
     */
    private fun setupAppResolver() {
        engine.setAppResolver(AppResolver { sourcePort, sourceIP, destIP, destPort ->
            try {
                val identity = appNameResolver.resolveIdentity(
                    sourcePort.toInt(), sourceIP, destIP, destPort.toInt()
                )
                if (identity.packageName.isEmpty()) return@AppResolver ""
                identity.packageName
            } catch (e: Exception) {
                Timber.e(e, "App resolve failed")
                ""
            }
        })
    }

    /**
     * Set up the firewall checker for per-app DNS blocking.
     * Receives the already resolved appName from Go, and checks [FirewallManager.shouldBlock].
     */
    private fun setupFirewallChecker() {
        engine.setFirewallChecker(FirewallChecker { appName ->
            val fwManager = firewallManagerProvider() ?: return@FirewallChecker false
            try {
                if (appName.isEmpty()) return@FirewallChecker false
                // appName here is actually the packageName from AppResolver
                fwManager.shouldBlock(appName)
            } catch (e: Exception) {
                Timber.e(e, "Firewall check failed")
                false
            }
        })
    }

    /**
     * Set the DNS log callback.
     */
    private fun setupLogCallback() {
        engine.setLogCallback { domain, blocked, queryType, responseTimeMs, packageNameOrAppName, resolvedIP, blockedBy ->
            scope.launch(Dispatchers.IO) {
                try {
                    // Try to resolve the user-friendly App Name string from the package name
                    val friendlyAppName = if (packageNameOrAppName.isNotEmpty() && packageNameOrAppName.contains(".")) {
                        try {
                            val pm = context.packageManager
                            val info = pm.getApplicationInfo(packageNameOrAppName, 0)
                            pm.getApplicationLabel(info).toString()
                        } catch (e: Exception) {
                            packageNameOrAppName
                        }
                    } else {
                        packageNameOrAppName
                    }

                    val entry = DnsLogEntry(
                        domain = domain,
                        isBlocked = blocked,
                        queryType = dnsQueryTypeToString(queryType.toInt()),
                        responseTimeMs = responseTimeMs,
                        appName = friendlyAppName,
                        packageName = packageNameOrAppName,
                        resolvedIp = resolvedIP,
                        blockedBy = blockedBy,
                        timestamp = System.currentTimeMillis(),
                    )
                    dnsLogDao.insert(entry)
                } catch (e: Exception) {
                    Timber.e(e, "Error logging DNS query for $domain")
                }
            }
        }
    }

    /**
     * Start the Go tunnel engine.
     * This method blocks the calling thread until [stop] is called.
     *
     * @param vpnInterface The TUN file descriptor from VpnService
     * @param wgConfigJson Optional WireGuard config JSON
     * @param httpsFilteringEnabled True if MITM proxy should be started
     * @param selectedBrowsers Set of package names allowed for MITM
     * @param certDir Directory to store the proxy's root CA certificate
     */
    fun start(
        vpnInterface: android.os.ParcelFileDescriptor, 
        wgConfigJson: String = "",
        httpsFilteringEnabled: Boolean = false,
        selectedBrowsers: Set<String> = emptySet(),
        certDir: String = "",
        socketProtector: ((Int) -> Boolean)? = null
    ) {
        if (isRunning) return
        isRunning = true

        // 1. Synchronize the MITM Proxy State before starting the tunnel
        if (httpsFilteringEnabled && certDir.isNotEmpty()) {
            try {
                // Map package names to UIDs and set them in Go
                val pm = context.packageManager
                val uids = selectedBrowsers.mapNotNull { pkg ->
                    try {
                        pm.getPackageUid(pkg, 0)
                    } catch (e: Exception) {
                        null
                    }
                }.joinToString(",")
                
                // Always set UIDs (empty string clears the filter in Go)
                engine.setMitmAllowedUIDs(uids)

                // Start the MITM Proxy in Go (listens on 127.0.0.1:8080)
                engine.startMitmProxy("127.0.0.1:8080", certDir)
                Timber.d("MITM Proxy automatically started on VPN boot")
            } catch (e: Exception) {
                Timber.e(e, "Failed to auto-start MITM proxy on VPN boot")
            }
        }

        setupAppResolver()
        setupDomainChecker()
        setupFirewallChecker()
        setupLogCallback()

        // Give Go the paths to the Mmap logs so it can read them natively for max speed
        updateTries()
        updateCosmeticRules()

        val fd = vpnInterface.fd
        Timber.d("Starting Go tunnel engine with fd=$fd, wg=${wgConfigJson.isNotEmpty()}")

        // Create socket protector that delegates to VpnService.protect() (if provided)
        val protector = SocketProtector { fd ->
            socketProtector?.invoke(fd.toInt()) ?: false
        }

        // Start the Go engine (this blocks the thread)
        // WireGuard setup happens atomically inside Go before any packets are read.
        engine.start(fd.toLong(), protector, wgConfigJson)
    }

    /**
     * Start the Go engine in Standalone DNS server mode (for Root/Proxy Mode).
     */
    suspend fun startStandalone(port: Int): Boolean {
        // Ensure any previous engine is fully stopped to release the port
        if (isRunning) {
            stop()
            delay(300) // Give OS time to release the socket
        }

        setupAppResolver()
        setupDomainChecker()
        setupFirewallChecker()
        setupLogCallback()

        updateTries()
        updateCosmeticRules()

        Timber.d("Starting Go tunnel engine in STANDALONE mode on port $port")
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()

        scope.launch(Dispatchers.IO) {
            try {
                launch {
                    delay(500)
                    if (!deferred.isCompleted) {
                        isRunning = true
                        deferred.complete(true)
                    }
                }
                engine.startStandalone(port.toLong())
            } catch (e: Exception) {
                Timber.e(e, "Go standalone engine crashed or failed to start")
                isRunning = false
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                }
            }
        }

        return try {
            withTimeout(2000) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            Timber.e("Timeout waiting for Go engine to start")
            isRunning = false
            false
        }
    }

    /**
     * Stop the Go tunnel engine.
     */
    fun stop() {
        isRunning = false
        engine.stop()
        Timber.d("Go tunnel engine stopped")
    }

    /**
     * Update the Go engine with the latest Trie and Bloom Filter file paths dynamically.
     * Paths are CSV-formatted strings (e.g., "path1,path2,path3").
     */
    fun updateTries() {
        engine.setTries(
            filterRepo.getAdTriePath(),
            filterRepo.getSecurityTriePath(),
            filterRepo.getAdBloomPath(),
            filterRepo.getSecurityBloomPath()
        )
    }

    /**
     * Load the latest cosmetic rules from the cache and send them to the Go engine.
     */
    fun updateCosmeticRules() {
        try {
            val cssPath = filterRepo.getCosmeticCssPath()
            if (cssPath != null) {
                val file = java.io.File(cssPath)
                if (file.exists() && file.length() > 0) {
                    val cssSnippet = file.readText()
                    engine.setCosmeticCSS(cssSnippet)
                    Timber.d("Sent ${cssSnippet.length} bytes of cosmetic CSS to Go engine")
                } else {
                    engine.setCosmeticCSS("")
                }
            } else {
                engine.setCosmeticCSS("")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load cosmetic CSS for engine")
            engine.setCosmeticCSS("")
        }
    }

    /**
     * Get engine statistics as JSON.
     */
    fun getStats(): String {
        return engine.stats
    }

    companion object {
        /**
         * Convert DNS query type number to human-readable string.
         * DNS types defined in RFC 1035 & 3596.
         */
        private fun dnsQueryTypeToString(type: Int): String = when (type) {
            1 -> "A"
            28 -> "AAAA"
            5 -> "CNAME"
            else -> "OTHER"
        }
    }
}
