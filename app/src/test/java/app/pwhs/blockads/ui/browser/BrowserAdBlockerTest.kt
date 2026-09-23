package app.pwhs.blockads.ui.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import app.pwhs.blockads.ui.browser.rules.BrowserRuleDefaults
import app.pwhs.blockads.ui.browser.rules.BrowserRulePackage
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class BrowserAdBlockerTest {

    @After
    fun restoreDefaults() = resetBrowserAdBlocker()

    private fun request(url: String?): WebResourceRequest = mockk {
        every { this@mockk.url } returns url?.let(Uri::parse)
    }

    private fun blocks(url: String) = BrowserAdBlocker.shouldBlock(request(url))

    @Test
    fun `host suffix matches the domain and its subdomains only`() {
        assertTrue(blocks("https://doubleclick.net/x"))
        assertTrue(blocks("https://ads.doubleclick.net/x"))
        assertFalse(blocks("https://notdoubleclick.net/x"))
        assertFalse(blocks("https://doubleclick.net.example.com/x"))
    }

    @Test
    fun `host matching is case-insensitive`() {
        assertTrue(blocks("https://ADS.DoubleClick.NET/x"))
    }

    @Test
    fun `gambling keywords match inside the host`() {
        assertTrue(blocks("https://www.kubet-promo.io/"))
        assertFalse(blocks("https://example.com/kubet"))
    }

    @Test
    fun `ad path patterns match anywhere in the URL`() {
        assertTrue(blocks("https://cdn.example.com/static/ads.js"))
        assertTrue(blocks("https://example.com/PageAd/x"))
        assertFalse(blocks("https://example.com/articles/1"))
    }

    @Test
    fun `requests without a URL or host are allowed`() {
        assertFalse(BrowserAdBlocker.shouldBlock(request(null)))
        assertFalse(blocks("about:blank"))
    }

    @Test
    fun `top-level navigation uses the same decision as subresources`() {
        assertTrue(BrowserAdBlocker.shouldBlockNavigation(request("https://ads.doubleclick.net/"), "https://example.com"))
        assertFalse(BrowserAdBlocker.shouldBlockNavigation(request("https://example.com/"), null))
    }

    @Ignore("Suspected: ad path patterns are raw substrings, so navigating to an article such as /wiki/Catfish is blocked")
    @Test
    fun `path patterns do not block unrelated top-level pages`() {
        assertFalse(BrowserAdBlocker.shouldBlockNavigation(request("https://en.wikipedia.org/wiki/Catfish"), null))
    }

    @Ignore("Suspected: gambling keywords are raw host substrings, so 'bc.game' blocks abc.gamespot.com")
    @Test
    fun `keywords do not match across unrelated hosts`() {
        assertFalse(blocks("https://abc.gamespot.com/"))
    }

    @Test
    fun `applyRulePackage replaces non-empty lists and keeps the rest`() {
        BrowserAdBlocker.applyRulePackage(
            BrowserRulePackage(adDomains = listOf("tracker.test"), gamblingKeywords = emptyList(), adPathPatterns = emptyList())
        )
        assertEquals(1, BrowserAdBlocker.domainsCount)
        assertTrue(blocks("https://a.tracker.test/"))
        assertFalse(blocks("https://ads.doubleclick.net/"))
        assertEquals(BrowserRuleDefaults.GAMBLING_POPUNDER_KEYWORDS.size, BrowserAdBlocker.keywordsCount)
        assertTrue(blocks("https://cdn.example.com/static/ads.js"))
    }

    @Test
    fun `blocked response fails reads like ERR_BLOCKED_BY_CLIENT`() {
        val response = BrowserAdBlocker.createBlockedResponse()
        assertEquals("text/plain", response.mimeType)
        val error = runCatching { response.data.read() }.exceptionOrNull()
        assertTrue(error is IOException && error.message == "ERR_BLOCKED_BY_CLIENT")
        assertTrue(runCatching { response.data.read(ByteArray(4), 0, 4) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `surrogates stub known ad scripts`() {
        val adx = BrowserAdBlocker.getSurrogateResponse("https://cdn.site/inplayer-adx.js")!!
        assertEquals(200, adx.statusCode)
        assertTrue(adx.data.readBytes().decodeToString().contains("window.show_adx = 0"))

        val adsense = BrowserAdBlocker.getSurrogateResponse("https://pagead2.googlesyndication.com/adsbygoogle.js")!!
        assertTrue(adsense.data.readBytes().decodeToString().contains("adsbygoogle.loaded = true"))

        for (url in listOf("https://x/ads.js", "https://mc.yandex.ru/metrika/tag.js", "https://x/tag.min.js")) {
            val empty = BrowserAdBlocker.getSurrogateResponse(url)
            assertNotNull(url, empty)
            assertEquals("application/javascript", empty!!.mimeType)
            assertEquals(0, empty.data.readBytes().size)
        }
        assertEquals("*", adx.responseHeaders["Access-Control-Allow-Origin"])
    }

    @Test
    fun `analytics beacons get a 1x1 gif`() {
        val pixel = BrowserAdBlocker.getSurrogateResponse("https://www.google-analytics.com/collect?v=1")!!
        assertEquals("image/gif", pixel.mimeType)
        val bytes = pixel.data.readBytes()
        assertArrayEquals("GIF89a".toByteArray(), bytes.copyOf(6))
    }

    @Test
    fun `other URLs have no surrogate`() {
        assertNull(BrowserAdBlocker.getSurrogateResponse("https://example.com/app.js"))
    }

    @Test
    fun `spoofed UA keeps the Chrome major version and drops WebView markers`() {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/131.0.6778.39 Mobile Safari/537.36"
        val spoofed = BrowserAdBlocker.spoofChromeUserAgent(ua)
        assertTrue(spoofed.contains("Chrome/131.0.0.0"))
        assertFalse(spoofed.contains("; wv"))
        assertFalse(spoofed.contains("Version/4.0"))
        assertTrue(BrowserAdBlocker.spoofChromeUserAgent("weird").contains("Chrome/128.0.0.0"))
    }

    @Test
    fun `sanitizeSearchUrl mirrors the view model's URL resolution with Google`() {
        assertEquals("http://a.b", BrowserAdBlocker.sanitizeSearchUrl(" http://a.b "))
        assertEquals("https://example.com", BrowserAdBlocker.sanitizeSearchUrl("example.com"))
        assertEquals("https://www.google.com/search?q=cats%20dogs", BrowserAdBlocker.sanitizeSearchUrl("cats dogs"))
    }
}

/** BrowserAdBlocker is a process-wide object; restore the bundled lists between tests. */
fun resetBrowserAdBlocker() {
    BrowserAdBlocker.applyRulePackage(
        BrowserRulePackage(
            adDomains = BrowserRuleDefaults.AD_HOST_SUFFIXES,
            gamblingKeywords = BrowserRuleDefaults.GAMBLING_POPUNDER_KEYWORDS,
            adPathPatterns = BrowserRuleDefaults.AD_PATH_PATTERNS,
        )
    )
}
