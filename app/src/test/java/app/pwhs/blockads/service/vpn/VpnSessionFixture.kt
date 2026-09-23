package app.pwhs.blockads.service.vpn

import android.os.ParcelFileDescriptor
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol
import app.pwhs.blockads.service.VpnState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** A [VpnSessionController] on virtual time with recording fakes for every collaborator. */
class VpnSessionFixture(test: TestScope) {
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val status = VpnStatusStore()
    val events = mutableListOf<String>()
    val states = mutableListOf<VpnState>()
    val vpnEnabledWrites = mutableListOf<Boolean>()
    val phases = mutableListOf<String>()

    /** Establish results, consumed in order; the last one repeats. */
    val tunnelResults = ArrayDeque<TunnelResult>()
    var establishCalls = 0
    val tunnels = mutableListOf<ParcelFileDescriptor>()
    var configGate: CompletableDeferred<Unit>? = null
    var configError: Exception? = null
    var networkAvailable = true
    val networkAvailableFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var elapsed = 1_000_000L
    private val finalizers = mutableListOf<() -> Unit>()

    val config = StartupConfig(
        upstreamDns = "1.1.1.1", fallbackDns = "8.8.8.8", dnsResponseType = "0.0.0.0",
        dnsProtocol = DnsProtocol.PLAIN, dohUrl = "", whitelistedApps = setOf("com.bank"),
        safeSearchEnabled = false, youtubeRestrictedMode = false, firewallEnabled = false,
        dnsProviderId = null, firewallManager = null,
    )

    private val host = object : VpnSessionHost {
        override fun getString(resId: Int) = "phase#$resId"
        override fun enterForeground() { events += "foreground" }
        override fun updateNotification() {
            events += "notify"
            phases += controller.connectingPhase
        }
        override fun showRevokedNotification() { events += "revoked-notice" }
        override fun showStoppedNotification() { events += "stopped-notice" }
        override fun stopForeground(removeNotification: Boolean) { events += "stopForeground(remove=$removeNotification)" }
        override fun stopSelf() { events += "stopSelf" }
        override fun refreshPrivateDnsState() { events += "privateDns" }
        override fun broadcastWidgetUpdate() { events += "widget" }
        override fun logBatteryStatus() { events += "battery" }
        override fun scheduleStopFinalization(onFinalized: () -> Unit) { finalizers += onFinalized }
        override fun onFullyStopped() { events += "fullyStopped" }
    }

    private val engine = object : VpnSessionEngine {
        override suspend fun prepareStartupConfig(): StartupConfig {
            events += "prepare"
            configGate?.await()
            configError?.let { throw it }
            return config
        }
        override suspend fun configure(config: StartupConfig) { events += "configure" }
        override fun startFilterUpdateWatcher(scope: CoroutineScope) { events += "watcher" }
        override suspend fun startTunnel(vpnInterface: ParcelFileDescriptor, resolvedWgConfigJson: String, httpsFilteringEnabled: Boolean) {
            events += "startTunnel(${tunnels.indexOf(vpnInterface)},$resolvedWgConfigJson)"
        }
        override fun stop() { events += "engineStop" }
    }

    private val network = object : VpnNetworkWatch {
        override val networkAvailableFlow = this@VpnSessionFixture.networkAvailableFlow
        override fun startNetworkMonitoring() { events += "netWatch+" }
        override fun stopNetworkMonitoring() { events += "netWatch-" }
        override fun isNetworkAvailable() = networkAvailable
        override fun cancelNetworkSwitch() { events += "cancelSwitch" }
        override fun startPeriodicMonitoring() { events += "periodic+" }
        override fun stopPeriodicMonitoring() { events += "periodic-" }
    }

    private val prefs: AppPreferences = mockk {
        coEvery { getHttpsFilteringEnabledSnapshot() } returns false
        coEvery { setVpnEnabled(any()) } answers { vpnEnabledWrites += firstArg<Boolean>() }
        every { lastMilestoneBlocked } returns flowOf(0L)
    }
    private val dao: DnsLogDao = mockk { coEvery { getBlockedCountSync() } returns 7 }

    val controller: VpnSessionController = VpnSessionController(
        scope = scope,
        status = status,
        host = host,
        engine = engine,
        network = network,
        appPrefs = prefs,
        dnsLogDao = dao,
        nextMilestoneThreshold = { null },
        establishTunnel = { establish() },
        clock = { 1_700_000_000_000L + test.testScheduler.currentTime },
        elapsedRealtime = { elapsed },
        ioDispatcher = dispatcher,
        mainDispatcher = dispatcher,
    )

    init {
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
            status.state.collect { states += it }
        }
    }

    val state: VpnState get() = status.state.value

    private fun establish(): TunnelResult {
        establishCalls++
        val next = if (tunnelResults.size > 1) tunnelResults.removeFirst() else tunnelResults.firstOrNull()
        return when (next) {
            null -> success().also { tunnels += it.vpnInterface }
            is TunnelResult.Success -> next.copy(vpnInterface = mockk(relaxed = true)).also { tunnels += it.vpnInterface }
            else -> next
        }
    }

    fun success(json: String = "") = TunnelResult.Success(mockk(relaxed = true), json)

    /** Runs pending stop finalizers, as the OS reporting the VPN transport gone. */
    fun finalizeStops() {
        val pending = finalizers.toList()
        finalizers.clear()
        pending.forEach { it() }
    }
}
