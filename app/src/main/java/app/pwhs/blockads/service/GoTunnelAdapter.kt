package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
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
import tunnel.UIDResolver
import java.net.InetAddress
import java.net.InetSocketAddress

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
     * Toggle the experimental userspace TCP/IP stack path (HTTPS
     * filtering refactor, Phases C+). When enabled, non-DNS packets
     * are diverted from the legacy Router into a gVisor-backed
     * userspace stack that terminates each TCP/UDP flow with per-app
     * UID visibility.
     *
     * Must be called BEFORE [start]; runtime toggling is not supported
     * in Phase C. Default false (legacy path).
     */
    fun setUseTcpStack(enabled: Boolean) {
        engine.setUseTcpStack(enabled)
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
     * Set up the UID resolver used by the userspace TCP/IP stack
     * (HTTPS filtering refactor, Phase B). For each terminated TCP/UDP
     * flow the stack calls [resolveUID] with the 5-tuple and expects
     * back the UID of the owning app, so downstream code can scope MITM
     * decisions per-app.
     *
     * Uses the official [ConnectivityManager.getConnectionOwnerUid] API
     * on Android 10+ (API 29+). On older devices the API is missing
     * and /proc/net/{tcp,udp} is SELinux-blocked, so we return
     * UIDUnknown (-1) and the stack treats the flow conservatively.
     */
    private fun setupUidResolver() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // No resolver available — stack will see UIDUnknown for every flow.
            Timber.d("UID resolver unavailable (API<29 or no ConnectivityManager)")
            return
        }
        engine.setUIDResolver(UIDResolver { protocol, localIP, localPort, remoteIP, remotePort ->
            try {
                val proto = when (protocol.toInt()) {
                    6 -> OsConstants.IPPROTO_TCP
                    17 -> OsConstants.IPPROTO_UDP
                    else -> return@UIDResolver -1L
                }
                val local = InetSocketAddress(InetAddress.getByName(localIP), localPort.toInt())
                val remote = InetSocketAddress(InetAddress.getByName(remoteIP), remotePort.toInt())
                cm.getConnectionOwnerUid(proto, local, remote).toLong()
            } catch (e: Exception) {
                // Race against socket teardown or invalid input — return
                // UIDUnknown so the stack can fall back gracefully.
                -1L
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
                // Prefer packageName so the LogCallback can resolve a real
                // app label. For system UIDs (netd 1052, dns_resolver, …)
                // there is no package — fall back to the friendly appName
                // so Go doesn't keep its "RootProxy" default.
                when {
                    identity.packageName.isNotEmpty() -> identity.packageName
                    identity.appName.isNotEmpty() -> identity.appName
                    else -> ""
                }
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

        // 1. Synchronize the MITM state before starting the tunnel.
        // HTTPS filtering now runs through the userspace TCP/IP stack
        // (Phase E): each TCP flow is terminated in Go with real
        // source-UID visibility, so per-app scoping (MITM only the
        // selected browsers) actually works on Android 10+. The legacy
        // VpnService HTTP proxy path is no longer used.
        if (httpsFilteringEnabled && certDir.isNotEmpty()) {
            try {
                val pm = context.packageManager
                val uids = selectedBrowsers.mapNotNull { pkg ->
                    try {
                        pm.getPackageUid(pkg, 0)
                    } catch (e: Exception) {
                        null
                    }
                }.joinToString(",")

                // Enable the stack, init CA + filter, register UIDs.
                engine.setUseTcpStack(true)
                engine.startStackMitm(certDir)
                engine.setMitmAllowedUIDs(uids)

                // Load curated passthrough domains (banking, payment,
                // gov, secure messaging, etc.) from assets so cert-
                // pinned apps and security-critical traffic don't
                // attempt MITM. Sourced from
                // github.com/pass-with-high-score/HttpsExclusions.
                try {
                    val passthrough = context.assets.open("https_passthrough.txt")
                        .bufferedReader().use { it.readText() }
                    engine.setExtraPassthroughSuffixes(passthrough)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to load https_passthrough.txt asset")
                }

                Timber.d("HTTPS filtering via userspace TCP/IP stack (browsers=${selectedBrowsers.size})")
            } catch (e: Exception) {
                Timber.e(e, "Failed to init stack MITM on VPN boot")
            }
        }

        setupAppResolver()
        setupDomainChecker()
        setupFirewallChecker()
        setupLogCallback()
        setupUidResolver()

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
        setupUidResolver()

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
     * Load the latest cosmetic rules + scriptlet rules from the cache
     * and send them to the Go engine.
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

        try {
            val sp = filterRepo.getScriptletsPath()
            if (sp != null) {
                val text = java.io.File(sp).readText()
                engine.setScriptletRules(text)
                Timber.d("Sent ${text.length} bytes of scriptlet rules to Go engine")
            } else {
                engine.setScriptletRules("")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load scriptlet rules for engine")
            engine.setScriptletRules("")
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
