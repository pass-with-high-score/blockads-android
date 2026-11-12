package app.pwhs.blockadstv.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import app.pwhs.blockadstv.data.dao.DnsLogDao
import app.pwhs.blockadstv.data.entities.DnsLogEntry
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.utils.AppNameResolver
import app.pwhs.blockadstv.utils.BlocklistInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import tunnel.AppResolver
import tunnel.AppUidResolver
import tunnel.DomainChecker
import tunnel.FirewallChecker
import tunnel.SocketProtector
import tunnel.UIDResolver
import java.net.InetAddress
import java.net.InetSocketAddress

class GoTunnelAdapter(
    private val context: Context,
    private val filterRepo: FilterListRepository,
    private val dnsLogDao: DnsLogDao,
    private val scope: CoroutineScope,
    private val appNameResolver: AppNameResolver,
    private val firewallManager: TvFirewallManager,
    private val recordLogProvider: () -> Boolean = { true },
) {
    private val engine = tunnel.Tunnel.newEngine()

    @Volatile
    private var isRunning = false

    fun configureDns(
        protocol: String,
        primary: String,
        fallback: String,
        dohUrl: String,
        odohRelayUrl: String = "",
    ) {
        engine.setDNS(protocol, primary, fallback, dohUrl)
        if (protocol.equals("ODOH", ignoreCase = true) || odohRelayUrl.isNotBlank()) {
            engine.setODoHRelay(odohRelayUrl)
        }
    }

    fun setBlockResponseType(responseType: String) {
        engine.setBlockResponseType(responseType)
    }

    fun setPreferredIPType(type: String) = engine.setPreferredIPType(type)

    fun setUseTcpStack(enabled: Boolean) = engine.setUseTcpStack(enabled)

    fun setFilterHttp3(enabled: Boolean) = engine.setFilterHttp3(enabled)

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
        engine.setAppUidResolver(AppUidResolver { uid ->
            try {
                context.packageManager.getPackagesForUid(uid.toInt())?.firstOrNull() ?: ""
            } catch (e: Exception) {
                ""
            }
        })
    }

    private fun setupAppResolver() {
        engine.setAppResolver(AppResolver { sourcePort, sourceIP, destIP, destPort ->
            try {
                val identity = appNameResolver.resolveIdentity(
                    sourcePort.toInt(), sourceIP, destIP, destPort.toInt()
                )
                when {
                    identity.packageName.isNotEmpty() -> identity.packageName
                    identity.appName.isNotEmpty() -> identity.appName
                    else -> ""
                }
            } catch (e: Exception) {
                ""
            }
        })
    }

    private fun setupFirewallChecker() {
        engine.setFirewallChecker(FirewallChecker { appName ->
            try {
                if (appName.isEmpty()) return@FirewallChecker false
                firewallManager.shouldBlock(appName)
            } catch (e: Exception) {
                Timber.e(e, "Firewall check failed for $appName")
                false
            }
        })
    }

    private fun setupLogCallback() {
        engine.setConnLogEnabled(recordLogProvider())
        val ownPackage = context.packageName

        engine.setLogCallback { domain, blocked, queryType, responseTimeMs, packageNameOrAppName, resolvedIP, blockedBy ->
            if (!recordLogProvider()) return@setLogCallback
            if (packageNameOrAppName == ownPackage) return@setLogCallback

            scope.launch(Dispatchers.IO) {
                try {
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

    fun start(
        vpnInterface: ParcelFileDescriptor,
        socketProtector: ((Int) -> Boolean)? = null,
        wgConfigJson: String = "",
        proxyProtocol: String = "",
        proxyConfigJson: String = "",
        filterHttp3: Boolean = false,
        blockDohBypass: Boolean = false,
        blockWebRtc: Boolean = false,
        useTcpStack: Boolean = false,
    ) {
        if (isRunning) return
        isRunning = true

        if (proxyProtocol.isNotEmpty() && proxyConfigJson.isNotEmpty()) {
            try {
                engine.setProxyNodeConfig(proxyProtocol, proxyConfigJson)
            } catch (e: Exception) {
                Timber.e(e, "Failed to configure proxy node in Go engine")
            }
        }

        engine.setUseTcpStack(useTcpStack)
        engine.setFilterHttp3(filterHttp3)

        setupAppResolver()
        setupDomainChecker()
        setupFirewallChecker()
        setupLogCallback()
        setupUidResolver()
        setupAppUidResolver()

        setBlockDohBypass(blockDohBypass)
        setBlockWebRtc(blockWebRtc)
        updateTries()
        loadGeoIPDatabase()

        val fd = vpnInterface.fd
        Timber.d("Starting Go tunnel engine with fd=$fd, wg=${wgConfigJson.isNotEmpty()}, hasProxy=${proxyConfigJson.isNotEmpty()}")

        val protector = SocketProtector { protectedFd ->
            socketProtector?.invoke(protectedFd.toInt()) ?: false
        }

        if (wgConfigJson.isNotEmpty()) {
            Timber.d("Starting Go tunnel engine in WIREGUARD mode (fd=$fd)")
            engine.start(fd.toLong(), protector, wgConfigJson)
        } else if (proxyConfigJson.isNotEmpty()) {
            engine.startFull(fd.toLong(), protector)
        } else {
            engine.start(fd.toLong(), protector, "")
        }
    }

    suspend fun startStandalone(port: Int): Boolean {
        if (isRunning) {
            stop()
            delay(300)
        }

        setupAppResolver()
        setupDomainChecker()
        setupFirewallChecker()
        setupLogCallback()
        setupUidResolver()
        updateTries()

        Timber.d("Starting TV Go tunnel engine in STANDALONE mode on port $port")
        val deferred = CompletableDeferred<Boolean>()

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
                Timber.e(e, "TV Go standalone engine crashed or failed to start")
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
        } catch (e: Exception) {
            Timber.e(e, "Timeout or error starting TV Go standalone engine")
            isRunning = false
            false
        }
    }

    fun isEngineRunning(): Boolean = engine.isRunning

    fun stop() {
        isRunning = false
        engine.stop()
        Timber.d("Go tunnel engine stopped")
    }

    fun updateTries() {
        engine.setTries(
            filterRepo.getAdTriePath(),
            filterRepo.getSecurityTriePath(),
            filterRepo.getAdBloomPath(),
            filterRepo.getSecurityBloomPath(),
        )
    }

    fun loadGeoIPDatabase() {
        try {
            val localFile = java.io.File(context.filesDir, "geoip_ipv4.bin")
            if (localFile.exists() && localFile.length() > 0) {
                val data = localFile.readBytes()
                engine.setGeoIPDatabaseBytes(data)
                Timber.d("GeoIP database loaded into Go engine from local storage (${data.size} bytes)")
                return
            }

            val loaded = BlocklistInfo.fromAsset(context, "preset/geoip_ipv4.bin")?.use { info ->
                engine.setGeoIPDatabaseFromFd(info.fd, info.startOffset, info.length)
                true
            } ?: false
            if (!loaded) {
                val data = context.assets.open("preset/geoip_ipv4.bin").use { it.readBytes() }
                engine.setGeoIPDatabaseBytes(data)
            }
            Timber.d("GeoIP database loaded into Go engine from asset")
        } catch (_: java.io.FileNotFoundException) {
            Timber.d("GeoIP database not yet downloaded, engine running without GeoIP ruleset")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load GeoIP database into Go engine")
        }
    }

    companion object {
        private fun dnsQueryTypeToString(type: Int): String = when (type) {
            1 -> "A"
            28 -> "AAAA"
            5 -> "CNAME"
            else -> "OTHER"
        }
    }
}
