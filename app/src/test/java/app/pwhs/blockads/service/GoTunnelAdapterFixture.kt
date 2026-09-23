package app.pwhs.blockads.service

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.utils.AppNameResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.robolectric.Shadows.shadowOf
import tunnel.AppResolver
import tunnel.AppUidResolver
import tunnel.DomainChecker
import tunnel.Engine
import tunnel.FirewallChecker
import tunnel.LogCallback
import tunnel.SocketProtector
import tunnel.Tunnel
import tunnel.UIDResolver
import java.util.Collections

/** A [GoTunnelAdapter] wired to a mocked Go [Engine]; captures every callback the adapter registers. */
class GoTunnelAdapterFixture {
    val app: Application = ApplicationProvider.getApplicationContext()
    val engine: Engine = mockk(relaxed = true)
    val filterRepo: FilterListRepository = mockk(relaxed = true)
    val logs: MutableList<DnsLogEntry> = Collections.synchronizedList(mutableListOf())
    val dnsLogDao: DnsLogDao = mockk { coEvery { insert(any()) } answers { logs += firstArg<DnsLogEntry>() } }
    val appNameResolver: AppNameResolver = mockk()
    var firewall: FirewallManager? = null
    var recordLogs = true
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val tunFd: ParcelFileDescriptor = mockk { every { fd } returns 42 }

    val domainChecker = slot<DomainChecker>()
    val firewallChecker = slot<FirewallChecker>()
    val appResolver = slot<AppResolver>()
    val appUidResolver = slot<AppUidResolver>()
    val uidResolver = slot<UIDResolver>()
    val logCallback = slot<LogCallback>()
    val protector = slot<SocketProtector>()

    init {
        mockkStatic(Tunnel::class)
        every { Tunnel.newEngine() } returns engine
        every { engine.setDomainChecker(capture(domainChecker)) } returns Unit
        every { engine.setFirewallChecker(capture(firewallChecker)) } returns Unit
        every { engine.setAppResolver(capture(appResolver)) } returns Unit
        every { engine.setAppUidResolver(capture(appUidResolver)) } returns Unit
        every { engine.setUIDResolver(capture(uidResolver)) } returns Unit
        every { engine.setLogCallback(capture(logCallback)) } returns Unit
        every { engine.startFull(any(), capture(protector)) } returns Unit
        every { engine.start(any(), capture(protector), any()) } returns Unit
    }

    val adapter = GoTunnelAdapter(
        context = app,
        filterRepo = filterRepo,
        dnsLogDao = dnsLogDao,
        scope = scope,
        appNameResolver = appNameResolver,
        firewallManagerProvider = { firewall },
        recordLogProvider = { recordLogs },
    )

    fun installApp(packageName: String, uid: Int, label: String = packageName) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.uid = uid
                nonLocalizedLabel = label
            }
        })
    }
}
