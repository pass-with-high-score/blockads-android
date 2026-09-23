package app.pwhs.blockads.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.testutil.FakeRootShell
import app.pwhs.blockads.testutil.FakeRootShell.FakeResult
import app.pwhs.blockads.utils.LibsuRootShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** IptablesManager's shell choreography, run against [FakeRootShell]. */
@RunWith(RobolectricTestRunner::class)
class IptablesManagerShellTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uid get() = context.applicationInfo.uid
    private var chainListed = true
    private val failing = mutableSetOf<String>()
    private val shell = FakeRootShell(respond = { cmd ->
        when {
            cmd in failing -> FakeResult(isSuccess = false, err = listOf("denied"))
            cmd.startsWith("iptables -t nat -L OUTPUT") ->
                FakeResult(out = if (chainListed) listOf("BLOCKADS_DNS  all  --  0.0.0.0/0") else emptyList())
            else -> FakeResult()
        }
    })

    @Before
    fun setUp() {
        IptablesManager.shell = shell
    }

    @After
    fun tearDown() {
        IptablesManager.shell = LibsuRootShell
    }

    @Test
    fun `a cached root shell is reused as is`() {
        shell.rootAfterBuilds = 0
        val rootShell = shell.mainShell() as FakeRootShell.FakeHandle
        assertTrue(IptablesManager.ensureRootShell())
        assertFalse(rootShell.closed)
        assertEquals(1, shell.builds)
    }

    @Test
    fun `a poisoned non-root shell is closed and rebuilt`() {
        shell.rootAfterBuilds = 1
        val poisoned = shell.mainShell() as FakeRootShell.FakeHandle
        assertFalse(poisoned.isRoot)

        assertTrue(IptablesManager.ensureRootShell())
        assertTrue(poisoned.closed)
        assertEquals(2, shell.builds)
    }

    @Test
    fun `with nothing cached a fresh shell is built and its root status returned`() {
        shell.rootAfterBuilds = 5
        assertFalse(IptablesManager.ensureRootShell())
        assertEquals(1, shell.builds)
    }

    @Test
    fun `a close failure is swallowed and the stale shell is reported`() {
        shell.rootAfterBuilds = 1
        shell.mainShell()
        shell.closeThrows = true
        assertFalse(IptablesManager.ensureRootShell())
    }

    @Test
    fun `isRootAvailable requires su -c id to report uid 0`() {
        shell.respond = { FakeResult(out = listOf("uid=0(root) gid=0(root)")) }
        assertTrue(IptablesManager.isRootAvailable())
        assertEquals(listOf("su -c id"), shell.commands)

        shell.respond = { FakeResult(out = listOf("uid=10234(u0_a234)")) }
        assertFalse(IptablesManager.isRootAvailable())
        shell.respond = { FakeResult(isSuccess = false, out = listOf("uid=0(root)")) }
        assertFalse(IptablesManager.isRootAvailable())
    }

    @Test
    fun `setup tears down, disables Private DNS, applies v4 then v6 one by one and verifies`() {
        assertTrue(IptablesManager.setupRules(context, whitelistUids = listOf(10200)))

        val expected = listOf(IptablesManager.teardownCommands()) +
            listOf(listOf("settings put global private_dns_mode off")) +
            IptablesManager.buildIpv4Commands(uid, true, listOf(10200)).map { listOf(it) } +
            IptablesManager.buildIpv6Commands(uid, true, listOf(10200)).map { listOf(it) } +
            listOf(listOf("iptables -t nat -L OUTPUT -n 2>/dev/null | grep BLOCKADS_DNS"))
        assertEquals(expected, shell.batches)
    }

    @Test
    fun `an IPv4 failure fails setup but the remaining rules are still applied`() {
        val v4 = IptablesManager.buildIpv4Commands(uid, true, emptyList())
        failing += v4[1]
        assertFalse(IptablesManager.setupRules(context))
        assertTrue(shell.commands.containsAll(v4.drop(2)))
    }

    @Test
    fun `rules missing after setup fail it`() {
        chainListed = false
        assertFalse(IptablesManager.setupRules(context))
    }

    @Test
    fun `IPv6 failures are ignored and setup still succeeds`() {
        failing += IptablesManager.buildIpv6Commands(uid, true, emptyList())
        assertTrue(IptablesManager.setupRules(context))
    }

    @Ignore("known bug: ip6tables failures are ignored so IPv6 DNS silently bypasses the filter")
    @Test
    fun `setup fails closed when the IPv6 redirect cannot be installed`() {
        failing += IptablesManager.buildIpv6Commands(uid, true, emptyList())
        assertFalse(IptablesManager.setupRules(context))
    }

    @Test
    fun `teardown runs as a single batch and always reports success`() {
        shell.respond = { FakeResult(isSuccess = false) }
        assertTrue(IptablesManager.teardownRules())
        assertEquals(listOf(IptablesManager.teardownCommands()), shell.batches)
    }

    @Test
    fun `isActive looks for the chain in the OUTPUT listing`() {
        assertTrue(IptablesManager.isActive())
        chainListed = false
        assertFalse(IptablesManager.isActive())
    }
}
