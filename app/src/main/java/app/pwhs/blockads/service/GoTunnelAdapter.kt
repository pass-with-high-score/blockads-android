package app.pwhs.blockads.service

import android.content.Context
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
import tunnel.AppDomainChecker
import tunnel.DomainChecker
import tunnel.FirewallChecker
import tunnel.SocketProtector

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
    internal val engine = tunnel.Tunnel.newEngine()

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
        engine.setAppDomainChecker(AppDomainChecker { domain, appName ->
            filterRepo.checkAppRule(domain, appName)
        })
    }

    private fun setupUidResolver() = GoTunnelResolvers.setupUidResolver(context, engine)

    private fun setupAppUidResolver() = GoTunnelResolvers.setupAppUidResolver(context, engine)

    private fun setupAppResolver() = GoTunnelResolvers.setupAppResolver(engine, appNameResolver)

    /**
     * Set up the firewall checker for per-app DNS blocking.
     * Receives the already resolved appName from Go, and checks [FirewallManager.shouldBlock].
     */
    private fun setupFirewallChecker() {
        val initialFwManager = firewallManagerProvider()
        engine.setFirewallEnabled(initialFwManager != null && initialFwManager.hasActiveRules())
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

    fun setSniProxyRules(rulesJson: String) {
        try {
            engine.setSniProxyRules(rulesJson)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update SNI proxy rules")
        }
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
        }.getOrDefault(context.getString(app.pwhs.blockads.R.string.app_name))

        engine.setLogCallback { domain, blocked, queryType, responseTimeMs, packageNameOrAppName, resolvedIP, blockedBy ->
            if (!recordLogProvider()) return@setLogCallback
            if (packageNameOrAppName == ownPackage || packageNameOrAppName == ownAppName) return@setLogCallback

            scope.launch(Dispatchers.IO) {
                try {
                    val (friendlyAppName, cleanPackageName) = DnsLogMapper.resolveAppIdentity(
                        packageNameOrAppName,
                        context,
                        appNameResolver
                    )

                    val resolvedDomain = IpDomainResolver.resolve(domain, resolvedIP)
                    IpDomainResolver.remember(domain, resolvedIP)
                    val countryCode = app.pwhs.blockads.data.geoip.GeoIpLookup.getCountryCode(resolvedIP, resolvedDomain)
                    val isConnection = blockedBy == "connection" || domain.startsWith("TCP ", ignoreCase = true) || domain.startsWith("UDP ", ignoreCase = true)
                    val effectiveQueryType = if (isConnection) {
                        if (domain.startsWith("UDP ", ignoreCase = true)) "UDP" else "TCP"
                    } else {
                        DnsLogMapper.dnsQueryTypeToString(queryType.toInt())
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

        engine.setMitmLogCallback { action, rule, method, resourceType, initiator, url, detail ->
            val logAction = when (action) {
                "BLOCKED" -> app.pwhs.blockads.data.logging.HttpLogAction.BLOCKED
                "SURROGATE" -> app.pwhs.blockads.data.logging.HttpLogAction.SURROGATE
                "SCRIPTLET" -> app.pwhs.blockads.data.logging.HttpLogAction.SCRIPTLET
                else -> app.pwhs.blockads.data.logging.HttpLogAction.ALLOWED
            }
            app.pwhs.blockads.data.logging.HttpDebugLogStore.record(
                source = "HTTPS", action = logAction, rule = rule, method = method,
                resourceType = resourceType, initiator = initiator, url = url, detail = detail
            )
        }
    }

    fun start(
        vpnInterface: android.os.ParcelFileDescriptor, 
        wgConfigJson: String = "",
        httpsFilteringEnabled: Boolean = false,
        selectedBrowsers: Set<String>? = null,
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
                val browsers = selectedBrowsers ?: TunnelRuleLoader.loadPresetBrowsers(context)
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
        } else {
            engine.stopStackMitm()
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

    fun reloadExtraPassthrough() {
        TunnelRuleLoader.loadExtraPassthrough(context, engine)
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

    fun setTailscaleTaildropReceiver(receiver: tunnel.TaildropReceiver) {
        runCatching { engine.setTailscaleTaildropReceiver(receiver) }
    }

    fun setTailscaleTaildropDir(dir: String) {
        runCatching { engine.setTailscaleTaildropDir(dir) }
    }

    fun sendTailscaleFile(target: String, fileName: String, filePath: String): Result<Unit> =
        runCatching { engine.sendTailscaleFile(target, fileName, filePath) }

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

    fun setTailscaleAdvertiseExitNode(enabled: Boolean) {
        runCatching { engine.setTailscaleAdvertiseExitNode(enabled) }
    }

    fun setTailscaleAdBlockEnabled(enabled: Boolean) {
        runCatching { engine.setTailscaleAdBlockEnabled(enabled) }
    }

    fun pingTailscalePeer(target: String): String =
        runCatching { engine.pingTailscalePeer(target) }.getOrDefault("""{"error":"Ping failed"}""")

    fun isTailscaleActive(): Boolean =
        runCatching { engine.isTailscaleActive }.getOrDefault(false)
}
