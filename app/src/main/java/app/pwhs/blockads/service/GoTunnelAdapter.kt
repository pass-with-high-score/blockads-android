package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.utils.AppNameResolver
import app.pwhs.blockads.utils.BlocklistInfo
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
 */
class GoTunnelAdapter(
    private val context: Context,
    private val filterRepo: FilterListRepository,
    private val dnsLogDao: DnsLogDao,
    private val scope: CoroutineScope,
    private val appNameResolver: AppNameResolver,
    private val firewallManagerProvider: () -> FirewallManager?,
    private val recordLogProvider: () -> Boolean,
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

    private fun setupDomainChecker() {
        engine.setDomainChecker(object : DomainChecker {
            override fun isBlocked(domain: String): Boolean = filterRepo.isBlocked(domain)
            override fun getBlockReason(domain: String): String = filterRepo.getBlockReason(domain)
            override fun hasCustomRule(domain: String): Long = filterRepo.hasCustomRule(domain)
        })
    }

    private fun setupUidResolver() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
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
                -1L
            }
        })
    }

    private fun setupAppUidResolver() {
        engine.setAppUidResolver(tunnel.AppUidResolver { uid ->
            try {
                context.packageManager.getPackagesForUid(uid.toInt())?.firstOrNull() ?: ""
            } catch (e: Exception) {
                ""
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
     * Mirror the "record logs" preference into the Go engine.
     *
     * Connection logging resolves the owning app for each new flow, which
     * costs a getConnectionOwnerUid plus a getPackagesForUid binder round
     * trip. The Kotlin log callback checks [recordLogProvider] too, but by
     * then Go has already paid for both — so the engine needs the flag
     * itself to skip the work entirely.
     */
    fun setConnLogEnabled(enabled: Boolean) {
        engine.setConnLogEnabled(enabled)
    }

    fun setBlockDohBypass(enabled: Boolean) {
        try {
            if (enabled) {
                val loaded = BlocklistInfo.fromAsset(context, "blocklist_doh.txt")?.use { info ->
                    engine.setDoHBlocklistFromFd(info.fd, info.startOffset, info.length)
                    true
                } ?: false
                if (!loaded) {
                    val list = context.assets.open("blocklist_doh.txt").bufferedReader().use { it.readText() }
                    engine.setDoHBlocklist(list)
                }
            } else {
                engine.setDoHBlocklist("")
            }
            Timber.d("DoH blocklist updated in Go engine (enabled=$enabled)")
        } catch (e: Exception) {
            Timber.w(e, "Failed to update DoH blocklist")
        }
    }

    /**
     * Set the DNS log callback.
     */
    private fun setupLogCallback() {
        engine.setConnLogEnabled(recordLogProvider())
        engine.setLogCallback { domain, blocked, queryType, responseTimeMs, packageNameOrAppName, resolvedIP, blockedBy ->
            if (!recordLogProvider()) return@setLogCallback

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
        filterHttp3: Boolean = false,
        blockDohBypass: Boolean = false,
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
                engine.setFilterHttp3(filterHttp3)

                // Load curated passthrough domains via zero-copy mmap
                try {
                    val loaded = BlocklistInfo.fromAsset(context, "https_passthrough.txt")?.use { info ->
                        engine.setExtraPassthroughSuffixesFromFd(info.fd, info.startOffset, info.length)
                        true
                    } ?: false
                    if (!loaded) {
                        val passthrough = context.assets.open("https_passthrough.txt")
                            .bufferedReader().use { it.readText() }
                        engine.setExtraPassthroughSuffixes(passthrough)
                    }
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
        setupAppUidResolver()

        // Load DoH blacklist asset to prevent DNS-over-HTTPS bypass if enabled (Issue #145)
        setBlockDohBypass(blockDohBypass)

        // Give Go the paths to the Mmap logs so it can read them natively for max speed
        updateTries()
        updateCosmeticRules()

        val fd = vpnInterface.fd
        Timber.d("Starting Go tunnel engine with fd=$fd, wg=${wgConfigJson.isNotEmpty()}")

        // Create socket protector that delegates to VpnService.protect() (if provided)
        val protector = SocketProtector { fd ->
            socketProtector?.invoke(fd.toInt()) ?: false
        }

        // Engine selection:
        //   • WireGuard → engine.start (WG handles its own full-route
        //     tunneling; setup happens atomically inside Go before any
        //     packets are read).
        //   • Otherwise → engine.startFull (full-tunnel direct-TUN engine;
        //     gVisor reads TUN directly — no DnsInterceptor/packetPipe
        //     bridge, so it doesn't deadlock under browser load). HTTPS
        //     MITM is layered on top when startStackMitm was called above;
        //     without it, full-tunnel still does all-app DNS filter +
        //     per-app firewall + protected passthrough.
        if (wgConfigJson.isNotEmpty()) {
            Timber.d("Starting Go tunnel engine in WIREGUARD mode (fd=$fd)")
            engine.start(fd.toLong(), protector, wgConfigJson)
        } else {
            Timber.d("Starting Go tunnel engine in FULL-TUNNEL mode (fd=$fd, mitm=${httpsFilteringEnabled && certDir.isNotEmpty()})")
            engine.startFull(fd.toLong(), protector)
        }
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
            if (cssPath != null && java.io.File(cssPath).exists()) {
                engine.setCosmeticCSSFromFile(cssPath)
            } else {
                engine.setCosmeticCSS("")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load cosmetic CSS for engine")
            engine.setCosmeticCSS("")
        }

        try {
            val sp = filterRepo.getScriptletsPath()
            if (sp != null && java.io.File(sp).exists()) {
                engine.setScriptletRulesFromFile(sp)
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
        private fun dnsQueryTypeToString(type: Int): String = when (type) {
            1 -> "A"; 28 -> "AAAA"; 5 -> "CNAME"; else -> "OTHER"
        }
    }
}
