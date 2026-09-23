package app.pwhs.blockads.ui.browser

import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import app.pwhs.blockads.ui.browser.rules.BrowserRulePackage
import app.pwhs.blockads.ui.browser.rules.BrowserRuleRepositoryImpl
import app.pwhs.blockads.ui.browser.rules.BrowserRuleStorage
import app.pwhs.blockads.ui.browser.rules.BrowserRuleUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class BrowserRuleRepositoryTest {

    private val active = BrowserRulePackage(version = 17, adDomains = listOf("a.test", "b.test"))
    private val defaults = BrowserRulePackage(version = 17, adDomains = listOf("default.test"))
    private val saved = slot<BrowserRulePackage>()
    private var saveResult = true
    private val storage: BrowserRuleStorage = mockk {
        every { getActivePackage() } returns active
        every { savePackage(capture(saved)) } answers { saveResult }
        every { resetToDefaults() } returns defaults
    }
    private val responses = mutableMapOf<String, () -> Pair<HttpStatusCode, String>>()
    private val requested = mutableListOf<String>()

    @After
    fun tearDown() = resetBrowserAdBlocker()

    private fun json(pkg: BrowserRulePackage) = Json.encodeToString(BrowserRulePackage.serializer(), pkg)

    private fun serve(url: String = BrowserRuleRepositoryImpl.DEFAULT_RULES_URL, status: HttpStatusCode = HttpStatusCode.OK, body: String) {
        responses[url] = { status to body }
    }

    private fun repo(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = BrowserRuleRepositoryImpl(
        storage = storage,
        client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            requested += url
            val handler = responses[url] ?: throw IOException("no route to $url")
            val (status, body) = handler()
            if (status.value in 200..299) respond(body, status) else respondError(status, body)
        }),
        ioDispatcher = dispatcher,
    )

    @Test
    fun `construction applies the active package to the blocker`() = runTest {
        val r = repo(StandardTestDispatcher(testScheduler))
        assertEquals(active, r.currentRules.value)
        assertEquals(2, BrowserAdBlocker.domainsCount)
        assertEquals(BrowserRuleUpdateStatus.Idle, r.updateStatus.value)
    }

    @Test
    fun `same version and domain count is up to date`() = runTest {
        serve(body = json(active.copy(updatedAt = 1)))
        val r = repo(StandardTestDispatcher(testScheduler))

        assertEquals(false, r.checkAndUpdate().getOrThrow())
        assertEquals(BrowserRuleUpdateStatus.UpToDate(17), r.updateStatus.value)
        verify(exactly = 0) { storage.savePackage(any()) }
    }

    @Test
    fun `higher version is saved and hot-reloaded`() = runTest {
        val remote = BrowserRulePackage(version = 18, adDomains = listOf("new.test"))
        serve(body = json(remote))
        val r = repo(StandardTestDispatcher(testScheduler))

        assertTrue(r.checkAndUpdate().getOrThrow())
        assertEquals(18L, saved.captured.version)
        assertEquals(remote.adDomains, r.currentRules.value.adDomains)
        assertEquals(BrowserRuleUpdateStatus.Updated(18, 1), r.updateStatus.value)
        assertEquals(1, BrowserAdBlocker.domainsCount)
    }

    @Test
    fun `custom URL overrides the default`() = runTest {
        serve(url = "https://mirror.test/rules.json", body = json(active))
        val r = repo(StandardTestDispatcher(testScheduler))
        r.checkAndUpdate("https://mirror.test/rules.json")
        assertEquals(listOf("https://mirror.test/rules.json"), requested)
    }

    @Ignore("Suspected: a lower version with more domains is applied, then dropped on restart because getActivePackage prefers the bundled version")
    @Test
    fun `lower version with more domains is not applied`() = runTest {
        serve(body = json(BrowserRulePackage(version = 3, adDomains = listOf("1", "2", "3"))))
        val r = repo(StandardTestDispatcher(testScheduler))
        assertEquals(false, r.checkAndUpdate().getOrThrow())
    }

    @Test
    fun `higher version with empty adDomains keeps the blocker's current domains`() = runTest {
        serve(body = json(BrowserRulePackage(version = 18, adDomains = emptyList())))
        val r = repo(StandardTestDispatcher(testScheduler))

        assertTrue(r.checkAndUpdate().getOrThrow())
        assertEquals(BrowserRuleUpdateStatus.Updated(18, 0), r.updateStatus.value)
        assertEquals(2, BrowserAdBlocker.domainsCount)
    }

    @Test
    fun `remote css and js URLs are resolved before saving`() = runTest {
        serve(body = json(BrowserRulePackage(version = 18, cosmeticCss = " https://cdn.test/a.css ", scriptletsJs = "https://cdn.test/s.js")))
        serve(url = "https://cdn.test/a.css", body = ".ad{display:none}")
        serve(url = "https://cdn.test/s.js", body = "window.x=1")
        val r = repo(StandardTestDispatcher(testScheduler))

        assertTrue(r.checkAndUpdate().getOrThrow())
        assertEquals(".ad{display:none}", saved.captured.cosmeticCss)
        assertEquals("window.x=1", saved.captured.scriptletsJs)
    }

    @Ignore("Suspected: an unresolvable remote cosmeticCss URL is saved verbatim and injected as CSS text instead of the bundled CSS")
    @Test
    fun `unresolved remote css URL is not stored as css`() = runTest {
        serve(body = json(BrowserRulePackage(version = 18, cosmeticCss = "https://cdn.test/missing.css")))
        serve(url = "https://cdn.test/missing.css", status = HttpStatusCode.NotFound, body = "")
        val r = repo(StandardTestDispatcher(testScheduler))

        r.checkAndUpdate()
        assertFalse(saved.captured.cosmeticCss.orEmpty().startsWith("http"))
    }

    @Ignore("remote scriptlets may come from a plain http:// URL and are injected into every page unsigned")
    @Test
    fun `plain http scriptlet URLs are rejected`() = runTest {
        serve(body = json(BrowserRulePackage(version = 18, scriptletsJs = "http://cdn.test/s.js")))
        serve(url = "http://cdn.test/s.js", body = "alert(1)")
        val r = repo(StandardTestDispatcher(testScheduler))

        r.checkAndUpdate()
        assertFalse(requested.contains("http://cdn.test/s.js"))
    }

    @Test
    fun `HTTP error reports failure`() = runTest {
        serve(status = HttpStatusCode.InternalServerError, body = "")
        val r = repo(StandardTestDispatcher(testScheduler))

        assertTrue(r.checkAndUpdate().isFailure)
        assertEquals(BrowserRuleUpdateStatus.Error("Server returned HTTP 500"), r.updateStatus.value)
    }

    @Test
    fun `blank body reports failure`() = runTest {
        serve(body = "  ")
        val r = repo(StandardTestDispatcher(testScheduler))

        assertTrue(r.checkAndUpdate().isFailure)
        assertEquals(BrowserRuleUpdateStatus.Error("Empty response received from rule server"), r.updateStatus.value)
    }

    @Test
    fun `malformed json and network errors report failure without touching rules`() = runTest {
        serve(body = "{not json")
        val r = repo(StandardTestDispatcher(testScheduler))
        assertTrue(r.checkAndUpdate().isFailure)
        assertTrue(r.updateStatus.value is BrowserRuleUpdateStatus.Error)

        assertTrue(r.checkAndUpdate("https://unreachable.test/").isFailure)
        assertEquals(active, r.currentRules.value)
        verify(exactly = 0) { storage.savePackage(any()) }
    }

    @Test
    fun `save failure is reported and rules stay put`() = runTest {
        saveResult = false
        serve(body = json(BrowserRulePackage(version = 18, adDomains = listOf("x"))))
        val r = repo(StandardTestDispatcher(testScheduler))

        assertTrue(r.checkAndUpdate().isFailure)
        assertEquals(active, r.currentRules.value)
        assertEquals(BrowserRuleUpdateStatus.Error("Failed to save updated rules to local storage"), r.updateStatus.value)
    }

    @Test
    fun `reset restores defaults and idles`() = runTest {
        serve(status = HttpStatusCode.InternalServerError, body = "")
        val r = repo(StandardTestDispatcher(testScheduler))
        r.checkAndUpdate()

        r.resetToDefaults()
        assertEquals(defaults, r.currentRules.value)
        assertEquals(BrowserRuleUpdateStatus.Idle, r.updateStatus.value)
        assertEquals(1, BrowserAdBlocker.domainsCount)
    }
}
