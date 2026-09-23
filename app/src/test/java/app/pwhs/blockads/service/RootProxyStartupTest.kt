package app.pwhs.blockads.service

import app.pwhs.blockads.testutil.FakeRootShell
import app.pwhs.blockads.utils.LibsuRootShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootProxyStartupTest {

    private var rootAttempts = 0
    private var engineStarts = 0
    private var ruleSetups = 0
    private var engineStops = 0

    @After
    fun tearDown() {
        IptablesManager.shell = LibsuRootShell
    }

    private suspend fun establish(
        retry: VpnRetryManager,
        root: (Int) -> Boolean = { true },
        engine: (Int) -> Boolean = { true },
        rules: (Int) -> Boolean = { true },
    ) = RootProxyStartup.establish(
        retryManager = { retry },
        ensureRootShell = { root(++rootAttempts) },
        startEngine = { engine(++engineStarts) },
        setupRules = { rules(++ruleSetups) },
        stopEngine = { engineStops++ },
    )

    @Test
    fun `boot gets 30 attempts and a manual start 10`() {
        assertEquals(30, RootProxyStartup.retryManagerFor(startedFromBoot = true).getMaxRetries())
        assertEquals(10, RootProxyStartup.retryManagerFor(startedFromBoot = false).getMaxRetries())
    }

    @Test
    fun `first-try success does not wait`() = runTest {
        assertTrue(establish(RootProxyStartup.retryManagerFor(false)))
        assertEquals(0L, currentTime)
        assertEquals(listOf(1, 1, 1, 0), listOf(rootAttempts, engineStarts, ruleSetups, engineStops))
    }

    @Test
    fun `no root ever gives up after 10 attempts, sleeping one last backoff after the final one`() = runTest {
        val retry = RootProxyStartup.retryManagerFor(false)
        assertFalse(establish(retry, root = { false }))
        assertEquals(10, rootAttempts)
        assertEquals(0, engineStarts)
        assertEquals(10, retry.getRetryCount())
        assertEquals(1_000L + 1_000 + 2_000 + 3_000 + 6 * 5_000, currentTime)
    }

    @Test
    fun `boot keeps trying for 30 attempts`() = runTest {
        assertFalse(establish(RootProxyStartup.retryManagerFor(true), root = { false }))
        assertEquals(30, rootAttempts)
    }

    @Test
    fun `su coming up late succeeds on a later attempt`() = runTest {
        assertTrue(establish(RootProxyStartup.retryManagerFor(true), root = { it >= 4 }))
        assertEquals(4, rootAttempts)
        assertEquals(1, engineStarts)
        assertEquals(1_000L + 1_000 + 2_000, currentTime)
    }

    @Test
    fun `an engine that will not start is retried without touching iptables`() = runTest {
        assertTrue(establish(RootProxyStartup.retryManagerFor(false), engine = { it >= 2 }))
        assertEquals(2, engineStarts)
        assertEquals(1, ruleSetups)
        assertEquals(0, engineStops)
    }

    @Test
    fun `iptables failure stops the engine before retrying`() = runTest {
        assertTrue(establish(RootProxyStartup.retryManagerFor(false), rules = { it >= 3 }))
        assertEquals(3, engineStarts)
        assertEquals(2, engineStops)
    }

    @Test
    fun `iptables never applying stops the engine after every attempt`() = runTest {
        assertFalse(establish(RootProxyStartup.retryManagerFor(false), rules = { false }))
        assertEquals(10, engineStops)
    }

    @Test
    fun `a poisoned shell is rebuilt on the next attempt`() = runTest {
        val shell = FakeRootShell(rootAfterBuilds = 1)
        shell.mainShell()
        IptablesManager.shell = shell
        val retry = RootProxyStartup.retryManagerFor(true)
        val ok = RootProxyStartup.establish(
            retryManager = { retry },
            ensureRootShell = IptablesManager::ensureRootShell,
            startEngine = { true },
            setupRules = { true },
            stopEngine = {},
        )
        assertTrue(ok)
        assertEquals(2, shell.builds)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `the retry manager is re-read each pass so a swapped budget takes over mid-loop`() = runTest {
        var retry = VpnRetryManager(maxRetries = 2)
        val result = RootProxyStartup.establish(
            retryManager = { retry },
            ensureRootShell = {
                if (++rootAttempts == 1) retry = VpnRetryManager(maxRetries = 5)
                false
            },
            startEngine = { true },
            setupRules = { true },
            stopEngine = {},
        )
        assertFalse(result)
        assertEquals(5, rootAttempts)
    }
}
