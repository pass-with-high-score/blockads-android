package app.pwhs.blockads.service

import app.pwhs.blockads.testutil.ShadowGoSeq
import app.pwhs.blockads.testutil.awaitTrue
import app.pwhs.blockads.utils.AppNameResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowGoSeq::class], instrumentedPackages = ["go", "tunnel"])
class GoTunnelAdapterTest {

    private val f = GoTunnelAdapterFixture()

    @After
    fun tearDown() {
        f.scope.cancel()
        unmockkAll()
    }

    @Test
    fun `configuration calls go straight to the engine`() {
        f.adapter.configureDns("doh", "1.1.1.1", "8.8.8.8", "https://dns.test/q")
        f.adapter.setBlockResponseType("NXDOMAIN")
        f.adapter.setSplitDNSZones("lan,internal")
        f.adapter.configureSafeSearch(safeSearchEnabled = true, youtubeRestricted = false)
        f.adapter.setUseTcpStack(true)
        f.adapter.setConnLogEnabled(false)
        every { f.engine.stats } returns "{}"

        verify { f.engine.setDNS("doh", "1.1.1.1", "8.8.8.8", "https://dns.test/q") }
        verify { f.engine.setBlockResponseType("NXDOMAIN") }
        verify { f.engine.setSplitDNSZones("lan,internal") }
        verify { f.engine.setSafeSearch(true) }
        verify { f.engine.setYouTubeRestricted(false) }
        verify { f.engine.setUseTcpStack(true) }
        verify { f.engine.setConnLogEnabled(false) }
        assertEquals("{}", f.adapter.getStats())
    }

    @Test
    fun `direct mode starts the full-tunnel engine without the TCP stack`() {
        f.adapter.start(f.tunFd)
        verify { f.engine.setUseTcpStack(false) }
        verify { f.engine.startFull(42L, any()) }
        verify(exactly = 0) { f.engine.start(any(), any(), any()) }
        verify(exactly = 0) { f.engine.startStackMitm(any()) }
    }

    @Test
    fun `a second start while running is ignored until stop`() {
        f.adapter.start(f.tunFd)
        f.adapter.start(f.tunFd)
        verify(exactly = 1) { f.engine.startFull(any(), any()) }
        f.adapter.stop()
        verify { f.engine.stop() }
        f.adapter.start(f.tunFd)
        verify(exactly = 2) { f.engine.startFull(any(), any()) }
    }

    @Test
    fun `wireguard mode never enables the MITM stack`() {
        f.adapter.start(f.tunFd, wgConfigJson = "{\"wg\":1}", httpsFilteringEnabled = true, certDir = "/certs")
        verify { f.engine.setUseTcpStack(false) }
        verify { f.engine.start(42L, any(), "{\"wg\":1}") }
        verify(exactly = 0) { f.engine.startStackMitm(any()) }
    }

    @Test
    fun `https filtering scopes MITM to the selected browsers' UIDs`() {
        f.installApp("com.android.chrome", 10_100)
        f.installApp("org.example.browser", 10_200)
        f.adapter.start(
            f.tunFd, httpsFilteringEnabled = true, certDir = "/certs",
            selectedBrowsers = setOf("com.android.chrome", "org.example.browser", "not.installed"), filterHttp3 = true,
        )
        verify { f.engine.setUseTcpStack(true) }
        verify { f.engine.startStackMitm("/certs") }
        verify { f.engine.setMitmAllowedUIDs("10100,10200") }
        verify { f.engine.setFilterHttp3(true) }
        verify { f.engine.startFull(42L, any()) }
    }

    @Test
    fun `https filtering without a cert dir stays off`() {
        f.adapter.start(f.tunFd, httpsFilteringEnabled = true, certDir = "")
        verify { f.engine.setUseTcpStack(false) }
        verify(exactly = 0) { f.engine.startStackMitm(any()) }
    }

    @Test
    fun `no saved browser selection falls back to the presets`() {
        f.installApp("com.android.chrome", 10_100)
        f.adapter.start(f.tunFd, httpsFilteringEnabled = true, certDir = "/certs")
        verify { f.engine.setMitmAllowedUIDs("10100") }
    }

    @Ignore("an explicit empty browser selection is indistinguishable from 'no choice', so presets get MITM'd")
    @Test
    fun `an explicit empty browser selection MITMs nothing`() {
        f.installApp("com.android.chrome", 10_100)
        f.adapter.start(f.tunFd, httpsFilteringEnabled = true, certDir = "/certs", selectedBrowsers = emptySet())
        verify { f.engine.setMitmAllowedUIDs("") }
    }

    @Test
    fun `socket protector delegates to the VPN service`() {
        val protected = mutableListOf<Int>()
        f.adapter.start(f.tunFd, socketProtector = { protected += it; true })
        assertTrue(f.protector.captured.protect(7))
        assertEquals(listOf(7), protected)
    }

    @Test
    fun `missing socket protector refuses to protect`() {
        f.adapter.start(f.tunFd)
        assertFalse(f.protector.captured.protect(7))
    }

    @Test
    fun `start pushes tries, cosmetic rules and the DoH blocklist`() {
        every { f.filterRepo.getAdTriePath() } returns "ad.trie"
        every { f.filterRepo.getSecurityTriePath() } returns "sec.trie"
        every { f.filterRepo.getAdBloomPath() } returns "ad.bloom"
        every { f.filterRepo.getSecurityBloomPath() } returns "sec.bloom"
        every { f.filterRepo.getScriptletsPath() } returns null
        f.adapter.start(f.tunFd, blockDohBypass = true)

        verify { f.engine.setTries("ad.trie", "sec.trie", "ad.bloom", "sec.bloom") }
        verify { f.engine.setCosmeticCSS(match { it.isNotEmpty() }) }
        verify { f.engine.setScriptletsRuntime(match { it.isNotEmpty() }) }
        verify { f.engine.setScriptletRules("") }
        verify { f.engine.setAdPathPatterns(match { it.isNotEmpty() }) }
        verify { f.engine.setDoHBlocklist(match { it.isNotEmpty() }) }
    }

    @Test
    fun `scriptlet rules load from file when present and DoH blocking can be cleared`() {
        val rules = File.createTempFile("scriptlets", ".txt").apply { writeText("x"); deleteOnExit() }
        every { f.filterRepo.getScriptletsPath() } returns rules.absolutePath
        f.adapter.updateCosmeticRules()
        verify { f.engine.setScriptletRulesFromFile(rules.absolutePath) }

        f.adapter.setBlockDohBypass(false)
        verify { f.engine.setDoHBlocklist("") }
    }

    @Test
    fun `standalone start reports success once the engine has run for a moment`() = runBlocking {
        assertTrue(f.adapter.startStandalone(5353))
        verify { f.engine.startStandalone(5353L) }
    }

    @Test
    fun `standalone start reports failure when the engine throws`() = runBlocking {
        every { f.engine.startStandalone(any()) } throws IllegalStateException("port in use")
        assertFalse(f.adapter.startStandalone(5353))
    }

    @Test
    fun `app resolver prefers package names and falls back to labels`() {
        f.adapter.start(f.tunFd)
        val resolver = f.appResolver.captured
        every { f.appNameResolver.resolveIdentity(any(), any(), any(), any()) } returnsMany listOf(
            AppNameResolver.AppIdentity("Chrome", "com.android.chrome"),
            AppNameResolver.AppIdentity("netd", ""),
            AppNameResolver.AppIdentity("", ""),
        )
        assertEquals("com.android.chrome", resolver.resolveApp(1, byteArrayOf(), byteArrayOf(), 53))
        assertEquals("netd", resolver.resolveApp(1, byteArrayOf(), byteArrayOf(), 53))
        assertEquals("", resolver.resolveApp(1, byteArrayOf(), byteArrayOf(), 53))

        every { f.appNameResolver.resolveIdentity(any(), any(), any(), any()) } throws IllegalStateException()
        assertEquals("", resolver.resolveApp(1, byteArrayOf(), byteArrayOf(), 53))
    }

    @Test
    fun `log callback records resolved app labels unless logging is off`() {
        f.installApp("com.example.app", 10_300, label = "Example")
        f.adapter.start(f.tunFd)
        val cb = f.logCallback.captured

        cb.onDNSQuery("ads.test", true, 1, 12, "com.example.app", "0.0.0.0", "EasyList")
        cb.onDNSQuery("v6.test", false, 28, 3, "netd", "::1", "")
        cb.onDNSQuery("gone.test", false, 5, 3, "com.gone.app", "", "")
        awaitTrue { f.logs.size == 3 }

        val byDomain = f.logs.associateBy { it.domain }
        assertEquals("Example", byDomain.getValue("ads.test").appName)
        assertEquals("A", byDomain.getValue("ads.test").queryType)
        assertTrue(byDomain.getValue("ads.test").isBlocked)
        assertEquals("AAAA", byDomain.getValue("v6.test").queryType)
        assertEquals("netd", byDomain.getValue("v6.test").appName)
        assertEquals("CNAME", byDomain.getValue("gone.test").queryType)
        assertEquals("com.gone.app", byDomain.getValue("gone.test").appName)

        f.recordLogs = false
        cb.onDNSQuery("quiet.test", false, 99, 1, "", "", "")
        Thread.sleep(100)
        assertEquals(3, f.logs.size)
    }

    @Test
    fun `domain and firewall checkers delegate safely`() {
        f.adapter.start(f.tunFd)
        every { f.filterRepo.isBlocked("ads.test") } returns true
        every { f.filterRepo.getBlockReason("ads.test") } returns "EasyList"
        every { f.filterRepo.hasCustomRule("ads.test") } returns 1L
        with(f.domainChecker.captured) {
            assertTrue(isBlocked("ads.test"))
            assertEquals("EasyList", getBlockReason("ads.test"))
            assertEquals(1L, hasCustomRule("ads.test"))
        }

        val fw = f.firewallChecker.captured
        assertFalse(fw.shouldBlock("com.example.app"))
        val manager = mockk<FirewallManager> { every { shouldBlock("com.example.app") } returns true }
        f.firewall = manager
        assertTrue(fw.shouldBlock("com.example.app"))
        assertFalse(fw.shouldBlock(""))
        every { manager.shouldBlock(any()) } throws IllegalStateException()
        assertFalse(fw.shouldBlock("com.example.app"))
    }

    @Test
    fun `uid resolvers map protocols and packages`() {
        f.installApp("com.example.app", 10_300)
        f.adapter.start(f.tunFd)
        assertEquals(-1L, f.uidResolver.captured.resolveUID(1, "10.0.0.2", 1234, "1.1.1.1", 443))
        assertEquals("", f.appUidResolver.captured.packageForUid(99_999))
        assertEquals("com.example.app", f.appUidResolver.captured.packageForUid(10_300))
    }
}
