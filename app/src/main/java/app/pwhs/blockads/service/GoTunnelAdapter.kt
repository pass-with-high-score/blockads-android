package app.pwhs.blockads.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import app.pwhs.blockads.R
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

    init {
        TunnelRuleLoader.loadGeoIPDatabase(context, engine)
    }

    /**
     * Hot-reload the GeoIP database into the Go engine when a new version is downloaded.
     */
    fun reloadGeoIp() = TunnelRuleLoader.loadGeoIPDatabase(context, engine)

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
        odohRelayUrl: String = "",
    ) {
        engine.setDNS(protocol, primary, fallback, dohUrl)
        if (protocol.equals("ODOH", ignoreCase = true) || odohRelayUrl.isNotEmpty()) {
            engine.setODoHRelay(odohRelayUrl)
        }
    }

    fun setBlockResponseType(responseType: String) = engine.setBlockResponseType(responseType)

    fun setSplitDNSZones(zones: String) = engine.setSplitDNSZones(zones)

    fun configureSafeSearch(safeSearchEnabled: Boolean, youtubeRestricted: Boolean) {
        engine.setSafeSearch(safeSearchEnabled)
        engine.setYouTubeRestricted(youtubeRestricted)
    }

    fun setPreferredIPType(type: String) = engine.setPreferredIPType(type)

    fun setUseTcpStack(enabled: Boolean) = engine.setUseTcpStack(enabled)

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

    fun setBlockWebRtc(enabled: Boolean) {
        engine.setBlockWebRTC(enabled)
    }

    /**
     * Set the DNS log callback.
     */
    private fun setupLogCallback() {
        engine.setConnLogEnabled(recordLogProvider())
        val ownPackage = context.packageName
        val ownAppName = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(context.applicationInfo).toString()
        }.getOrDefault("BlockAds")

        engine.setLogCallback { domain, blocked, queryType, responseTimeMs, packageNameOrAppName, resolvedIP, blockedBy ->
            if (!recordLogProvider()) return@setLogCallback
            if (packageNameOrAppName == ownPackage || packageNameOrAppName == ownAppName) return@setLogCallback

            scope.launch(Dispatchers.IO) {
                try {
                    val (friendlyAppName, cleanPackageName) = when {
                        packageNameOrAppName == "uid:0" || packageNameOrAppName.equals("unknown", ignoreCase = true) -> {
                            context.getString(R.string.log_unknown_app) to ""
                        }
                        packageNameOrAppName.startsWith("uid:") -> {
                            val uid = packageNameOrAppName.removePrefix("uid:").toIntOrNull()
                            if (uid != null) {
                                val pkg = appNameResolver.getPackageNameForUid(uid)
                                val name = appNameResolver.getAppNameForUid(uid)
                                (if (name.isNotEmpty() && name != "uid:$uid") name else context.getString(R.string.log_unknown_app)) to pkg
                            } else {
                                context.getString(R.string.log_unknown_app) to ""
                            }
                        }
                        packageNameOrAppName.contains(".") -> {
                            val label = runCatching {
                                val pm = context.packageManager
                                pm.getApplicationLabel(pm.getApplicationInfo(packageNameOrAppName, 0)).toString()
                            }.getOrDefault(packageNameOrAppName)
                            label to packageNameOrAppName
                        }
                        else -> {
                            packageNameOrAppName to ""
                        }
                    }

                    val resolvedDomain = IpDomainResolver.resolve(domain, resolvedIP)
                    IpDomainResolver.remember(domain, resolvedIP)
                    val countryCode = app.pwhs.blockads.data.geoip.GeoIpLookup.getCountryCode(resolvedIP, resolvedDomain)
                    val isConnection = blockedBy == "connection" || domain.startsWith("TCP ", ignoreCase = true) || domain.startsWith("UDP ", ignoreCase = true)
                    val effectiveQueryType = if (isConnection) {
                        if (domain.startsWith("UDP ", ignoreCase = true)) "UDP" else "TCP"
                    } else {
                        dnsQueryTypeToString(queryType.toInt())
                    }
                    val entry = DnsLogEntry(
                        domain = resolvedDomain,
                        isBlocked = blocked,
                        queryType = effectiveQueryType,
                        responseTimeMs = responseTimeMs,
                        appName = friendlyAppName,
                        packageName = cleanPackageName,
                        resolvedIp = resolvedIP,
                        blockedBy = blockedBy,
                        countryCode = countryCode,
                        timestamp = System.currentTimeMillis()
                    )
                    dnsLogDao.insert(entry)
                } catch (e: Exception) {
                    Timber.e(e, "Error logging DNS query for $domain")
                }
            }
        }
    }

    fun start(
        vpnInterface: android.os.ParcelFileDescriptor, 
        wgConfigJson: String = "",
        httpsFilteringEnabled: Boolean = false,
        selectedBrowsers: Set<String> = emptySet(),
        certDir: String = "",
        filterHttp3: Boolean = false,
        blockDohBypass: Boolean = false,
        blockWebRtc: Boolean = false,
        socketProtector: ((Int) -> Boolean)? = null,
        ssConfigJson: String = "",
        proxyProtocol: String = "",
        proxyConfigJson: String = ""
    ) {
        if (isRunning) return
        isRunning = true

        try {
            if (proxyProtocol.isNotEmpty() && proxyConfigJson.isNotEmpty()) {
                engine.setProxyNodeConfig(proxyProtocol, proxyConfigJson)
            } else if (ssConfigJson.isNotEmpty()) {
                engine.setShadowsocksConfig(ssConfigJson)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to configure proxy node in Go engine")
        }

        val mitmMode = httpsFilteringEnabled &&
            certDir.isNotEmpty() &&
            wgConfigJson.isEmpty() &&
            proxyConfigJson.isEmpty() &&
            ssConfigJson.isEmpty()
        engine.setUseTcpStack(mitmMode)

        if (mitmMode) {
            try {
                val pm = context.packageManager
                val browsers = selectedBrowsers.ifEmpty { TunnelRuleLoader.loadPresetBrowsers(context) }
                val uids = browsers.mapNotNull { pkg ->
                    runCatching { pm.getPackageUid(pkg, 0) }.getOrNull()
                }.joinToString(",")

                engine.startStackMitm(certDir)
                engine.setMitmAllowedUIDs(uids)
                engine.setFilterHttp3(filterHttp3)

                TunnelRuleLoader.loadExtraPassthrough(context, engine)
                Timber.d("HTTPS filtering via userspace TCP/IP stack (browsers=${browsers.size}, uids=$uids)")
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

        setBlockDohBypass(blockDohBypass)
        setBlockWebRtc(blockWebRtc)

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
            Timber.d("Starting Go tunnel engine in FULL-TUNNEL mode (fd=$fd, mitm=$mitmMode)")
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
    fun isEngineRunning(): Boolean = engine.isRunning

    fun stop() {
        isRunning = false
        engine.stop()
        Timber.d("Go tunnel engine stopped")
    }

    fun updateTries() {
        engine.setTries(filterRepo.getAdTriePath(), filterRepo.getSecurityTriePath(), filterRepo.getAdBloomPath(), filterRepo.getSecurityBloomPath())
    }

    fun updateCosmeticRules() {
        TunnelRuleLoader.updateCosmeticRules(context, engine, filterRepo)
    }

    fun getStats(): String = engine.stats

    fun setRulesetConfig(content: String): Long = runCatching {
        val count = engine.setRulesetConfig(content)
        TunnelRuleLoader.loadCachedRemoteRulesets(context, engine)
        TunnelRuleLoader.applyProfileDnsIfPresent(engine) { p, f -> configureDns("PLAIN", p, f, "") }
        count
    }.onFailure { Timber.e(it, "Failed to set ruleset config") }.getOrDefault(0L)

    fun clearRulesetConfig() = engine.clearRulesetConfig()

    fun configureTailscale(state: String, url: String, key: String): String =
        runCatching { engine.configureTailscale(state, url, key) }.getOrDefault("{}")

    fun startTailscaleAuth(host: String): String =
        runCatching { engine.startTailscaleAuth(host) }.getOrDefault("")

    fun startTailscaleSession(host: String) {
        runCatching { engine.startTailscaleSession(host) }
    }

    fun stopTailscale() {
        runCatching { engine.stopTailscale() }
    }

    fun getTailscaleStatus(): String =
        runCatching { engine.tailscaleStatus }.getOrDefault("""{"state":"DISCONNECTED"}""")

    fun getTailscaleState(): String =
        runCatching { engine.tailscaleState }.getOrDefault("{}")

    fun setTailscaleExitNode(id: Long) {
        runCatching { engine.setTailscaleExitNode(id) }
    }

    fun isTailscaleActive(): Boolean =
        runCatching { engine.isTailscaleActive }.getOrDefault(false)

    companion object {
        private fun dnsQueryTypeToString(type: Int): String = when (type) {
            1 -> "A"; 28 -> "AAAA"; 5 -> "CNAME"; else -> "OTHER"
        }
    }
}
