package app.pwhs.blockadstv.service

import android.content.Context
import android.os.ParcelFileDescriptor
import app.pwhs.blockadstv.data.dao.DnsLogDao
import app.pwhs.blockadstv.data.entities.DnsLogEntry
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.utils.AppNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import tunnel.AppResolver
import tunnel.DomainChecker
import tunnel.FirewallChecker
import tunnel.SocketProtector

class GoTunnelAdapter(
    private val context: Context,
    private val filterRepo: FilterListRepository,
    private val dnsLogDao: DnsLogDao,
    private val scope: CoroutineScope,
    private val appNameResolver: AppNameResolver,
) {
    private val engine = tunnel.Tunnel.newEngine()

    @Volatile
    private var isRunning = false

    fun configureDns(protocol: String, primary: String, fallback: String, dohUrl: String) {
        engine.setDNS(protocol, primary, fallback, dohUrl)
    }

    fun setBlockResponseType(responseType: String) {
        engine.setBlockResponseType(responseType)
    }

    private fun setupDomainChecker() {
        engine.setDomainChecker(object : DomainChecker {
            override fun isBlocked(domain: String): Boolean = filterRepo.isBlocked(domain)
            override fun getBlockReason(domain: String): String = filterRepo.getBlockReason(domain)
            override fun hasCustomRule(domain: String): Long = filterRepo.hasCustomRule(domain)
        })
    }

    private fun setupAppResolver() {
        engine.setAppResolver(AppResolver { sourcePort, sourceIP, destIP, destPort ->
            try {
                val identity = appNameResolver.resolveIdentity(
                    sourcePort.toInt(), sourceIP, destIP, destPort.toInt()
                )
                if (identity.packageName.isEmpty()) return@AppResolver ""
                identity.packageName
            } catch (e: Exception) {
                ""
            }
        })
    }

    private fun setupFirewallChecker() {
        engine.setFirewallChecker(FirewallChecker { false })
    }

    private fun setupLogCallback() {
        engine.setLogCallback { domain, blocked, queryType, responseTimeMs, packageNameOrAppName, resolvedIP, blockedBy ->
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
    ) {
        if (isRunning) return
        isRunning = true

        setupAppResolver()
        setupDomainChecker()
        setupFirewallChecker()
        setupLogCallback()
        updateTries()

        val fd = vpnInterface.fd
        Timber.d("Starting Go tunnel engine with fd=$fd")

        val protector = SocketProtector { fd ->
            socketProtector?.invoke(fd.toInt()) ?: false
        }

        engine.start(fd.toLong(), protector, "")
    }

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

    companion object {
        private fun dnsQueryTypeToString(type: Int): String = when (type) {
            1 -> "A"
            28 -> "AAAA"
            5 -> "CNAME"
            else -> "OTHER"
        }
    }
}
