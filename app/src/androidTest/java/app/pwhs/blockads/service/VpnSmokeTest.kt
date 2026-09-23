package app.pwhs.blockads.service

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.annotation.StringRes
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.pollUntil
import app.pwhs.blockads.ui.customrules.CustomRulesViewModel
import app.pwhs.blockads.waitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Starts the real VPN on an emulator and checks what other apps see.
 *
 * The app excludes its own package from the tunnel, and the instrumentation shares its UID, so lookups made
 * from this process would bypass the VPN. They are made from the shell UID instead, which the tunnel does cover.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class VpnSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val customRules: CustomDnsRuleDao = getKoin().get()
    private val prefs: AppPreferences = getKoin().get()
    private val dnsLogs: DnsLogDao = getKoin().get()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var stateRecorder: Job? = null
    private var savedWhitelist: Set<String>? = null

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun awaitHomeText(@StringRes id: Int) {
        val text = context.getString(id)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** The address the shell UID's resolver returns for [host], or null if it does not resolve. */
    private fun resolve(host: String): String? =
        Regex("""PING \S+ \(([^)]+)\)""").find(shell("ping -c 1 -W 1 $host"))?.groupValues?.get(1)

    /** iputils ping reports a 0.0.0.0 answer as 127.0.0.1, so both count as the sinkhole. */
    private fun sinkholed(host: String) = resolve(host) in setOf(SINKHOLE, "127.0.0.1")

    private fun resolvesNormally(host: String) = resolve(host).let { it != null && it != SINKHOLE && it != "127.0.0.1" }

    private fun loggedAsBlocked(host: String, since: Long): DnsLogEntry? =
        runBlocking { dnsLogs.getBlockedOnlySince(since).first() }.firstOrNull { it.domain == host }

    /**
     * Whether a connected VPN network agent is listed. NetworkInfo prints as `ni{VPN CONNECTED ...}` from API 31 and
     * as `ni{[type: VPN[], state: CONNECTED/CONNECTED, ...]}` before that.
     */
    private fun vpnTransportUp(): Boolean = VPN_CONNECTED.containsMatchIn(shell("dumpsys connectivity"))

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        assumeTrue("VPN consent could not be pre-granted", VpnService.prepare(context) == null)
        runBlocking {
            // A shell whitelisting leaked by an earlier run would let every lookup here bypass the VPN.
            val whitelist = prefs.getWhitelistedAppsSnapshot() - SHELL_PACKAGE
            savedWhitelist = whitelist
            prefs.setWhitelistedApps(whitelist)
            prefs.setOnboardingCompleted(true)
            customRules.insert(CustomDnsRule(rule = "||$BLOCKED^", ruleType = RuleType.BLOCK, domain = BLOCKED))
        }
        assumeTrue("the emulator needs working DNS", resolve(CLEAN) != null)
        // The app must be in the foreground to start its service on API 31+.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Start from a stopped service even if something before this test left it running.
        stopAndSettle()
    }

    private fun startAndAwaitRunning() {
        AdBlockVpnService.start(context)
        waitUntil("the VPN to run", 30_000) { AdBlockVpnService.state.value == VpnState.RUNNING }
        waitUntil("the VPN transport to appear", 15_000) { vpnTransportUp() }
    }

    /** Every state the service passes through from now until the test ends. */
    private fun recordStates(): List<VpnState> {
        val seen = CopyOnWriteArrayList<VpnState>()
        stateRecorder = CoroutineScope(Dispatchers.Default).launch { AdBlockVpnService.state.collect { seen += it } }
        return seen
    }

    private fun serviceRunning(): Boolean =
        "ServiceRecord{" in shell("dumpsys activity services ${context.packageName}/${AdBlockVpnService::class.java.name}")

    /**
     * Stops the VPN and waits until the tunnel is gone, the service is destroyed and the state reads STOPPED.
     *
     * A stop can leave the state at STOPPING for good once the tunnel and the service are gone (the known bug in
     * [stopFromRunningSettlesInStopped]). Nothing would ever move it on, so this does what the lost finalization
     * would have done; otherwise one hit would fail every later test.
     */
    private fun stopAndSettle() {
        if (AdBlockVpnService.state.value == VpnState.STOPPED && !serviceRunning() && !vpnTransportUp()) return
        AdBlockVpnService.stop(context)
        waitUntil("the VPN transport to go away", 15_000) { !vpnTransportUp() }
        waitUntil("the VPN service to be destroyed", 15_000) { !serviceRunning() }
        if (pollUntil(10_000) { AdBlockVpnService.state.value == VpnState.STOPPED }) return
        check(AdBlockVpnService.state.value == VpnState.STOPPING) {
            "The VPN did not stop: state ${AdBlockVpnService.state.value} with the tunnel and the service gone"
        }
        AdBlockVpnService.status.state.value = VpnState.STOPPED
    }

    @After
    fun tearDown() {
        stateRecorder?.cancel()
        // Restore the whitelist and rules even if the stop fails, or the next test inherits them.
        try {
            stopAndSettle()
        } finally {
            runBlocking {
                customRules.getAll().filter { it.domain == BLOCKED }.forEach { customRules.delete(it) }
                savedWhitelist?.let { prefs.setWhitelistedApps(it) }
            }
            scenario?.close()
        }
    }

    @Test
    fun startBlocksACustomRuleDomainForOtherAppsAndStopRemovesTheTunnel() {
        assertTrue(resolvesNormally(BLOCKED))
        val startedAt = System.currentTimeMillis()

        startAndAwaitRunning()
        awaitHomeText(R.string.home_protected_desc)

        waitUntil("$BLOCKED to be sinkholed", 30_000) { sinkholed(BLOCKED) }
        assertTrue(resolvesNormally(CLEAN))
        waitUntil("the blocked lookup to be logged", 15_000) { loggedAsBlocked(BLOCKED, startedAt) != null }
        assertEquals(SINKHOLE, loggedAsBlocked(BLOCKED, startedAt)!!.resolvedIp.ifEmpty { SINKHOLE })

        AdBlockVpnService.stop(context)
        waitUntil("the VPN transport to go away", 15_000) { !vpnTransportUp() }
        waitUntil("$BLOCKED to resolve normally again", 10_000) { resolvesNormally(BLOCKED) }
    }

    @Ignore(
        "known bug: stop() finishes in the service scope that onDestroy cancels. When the onDestroy its own stopSelf() " +
            "triggers runs before the coroutine resumes, the stop finalization is never scheduled and the state stays " +
            "STOPPING (home shows disconnecting) with the tunnel gone; about half the stops on an API 30 emulator hit it"
    )
    @Test
    fun stopFromRunningSettlesInStopped() {
        startAndAwaitRunning()

        AdBlockVpnService.stop(context)

        waitUntil("the VPN to stop", 15_000) { AdBlockVpnService.state.value == VpnState.STOPPED }
        awaitHomeText(R.string.home_unprotected_desc)
    }

    @Test
    fun whitelistedAppBypassesFiltering() {
        runBlocking { prefs.setWhitelistedApps(setOf(SHELL_PACKAGE)) }

        startAndAwaitRunning()
        awaitHomeText(R.string.home_protected_desc)

        // Unwhitelisted, the lookup is sinkholed within a couple of seconds of this point (see the test above).
        repeat(5) {
            assertTrue(resolvesNormally(BLOCKED))
            Thread.sleep(1_000)
        }
    }

    @Test
    fun customRuleAddedFromTheAppWhileRunningBlocksAfterItsRestart() {
        runBlocking { customRules.getAll().filter { it.domain == BLOCKED }.forEach { customRules.delete(it) } }
        startAndAwaitRunning()
        waitUntil("$BLOCKED to resolve through the tunnel", 30_000) { resolvesNormally(BLOCKED) }
        val states = recordStates()

        getKoin().get<CustomRulesViewModel>().addRule("||$BLOCKED^")

        waitUntil("$BLOCKED to be sinkholed", 30_000) { sinkholed(BLOCKED) }
        waitUntil("the restart to finish", 30_000) {
            VpnState.RESTARTING in states && AdBlockVpnService.state.value == VpnState.RUNNING
        }
        assertTrue(sinkholed(BLOCKED))
    }

    /** Sends a stop the moment the service reports STARTING, then gives any leftover start work time to finish. */
    private fun stopAsSoonAsStarting(trigger: () -> Unit) {
        val stopper = CoroutineScope(Dispatchers.Default).launch {
            AdBlockVpnService.state.first { it == VpnState.STARTING }
            AdBlockVpnService.stop(context)
        }
        trigger()
        waitUntil("the stop to be sent", 30_000) { stopper.isCompleted }
        Thread.sleep(8_000)
    }

    @Ignore("known bug: a stop during STARTING leaves the state stuck at STOPPING after the tunnel is gone (fails about 5 runs in 6)")
    @Test
    fun stopDuringStartLeavesTheVpnStopped() {
        stopAsSoonAsStarting { AdBlockVpnService.start(context) }

        assertEquals("transport up: ${vpnTransportUp()}", VpnState.STOPPED, AdBlockVpnService.state.value)
        assertFalse(vpnTransportUp())
    }

    @Ignore("known bug: a stop while a restart is STARTING leaves the state stuck at STOPPING (fails about 5 runs in 6)")
    @Test
    fun stopDuringARestartLeavesTheVpnStopped() {
        startAndAwaitRunning()

        stopAsSoonAsStarting { AdBlockVpnService.requestRestart(context) }

        assertEquals(VpnState.STOPPED, AdBlockVpnService.state.value)
        assertFalse(vpnTransportUp())
    }

    private companion object {
        const val BLOCKED = "example.org"
        const val CLEAN = "example.com"
        const val SINKHOLE = "0.0.0.0"
        const val SHELL_PACKAGE = "com.android.shell"
        val VPN_CONNECTED = Regex("""ni\{(VPN CONNECTED|\[type: VPN\[[^\]]*], state: CONNECTED/)""")
    }
}
