package app.pwhs.blockads.ui.browser

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.pwhs.blockads.waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class BrowserBlockingTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val browser = BrowserHarness().apply { page("/", "<html><head><title>home</title></head><body>hi</body></html>") }

    @After
    fun tearDown() = browser.close()

    /** Fetches [path] and reads the whole body, so a blocked response fails whichever stage the error surfaces in. */
    private fun fetchBody(path: String) =
        browser.evalAsync("fetch('$path').then(r => r.text()).then(t => window.__result = 'ok:' + t, e => window.__result = 'err:' + e.name)")

    private fun fetchStatus(path: String) =
        browser.evalAsync("fetch('$path').then(r => window.__result = 'ok:' + r.status, e => window.__result = 'err:' + e.name)")

    @Test
    fun blockedFetchIsRejectedBeforeItLeavesTheDevice() {
        browser.launch("/")

        assertEquals("err:TypeError", fetchBody("/pagead/ad.gif"))
        assertFalse(browser.sawRequestWithin("/pagead/ad.gif", 500))
        assertEquals("ok:ok", fetchBody("/content.json"))
    }

    @Ignore("known bug: a blocked request gets 200 headers and then a failing body (net::ERR_FAILED), not ERR_BLOCKED_BY_CLIENT as createBlockedResponse documents")
    @Test
    fun blockedFetchFailsWithBlockedByClient() {
        browser.launch("/")

        assertEquals("err:TypeError", fetchStatus("/pagead/ad.gif"))

        waitUntil("an error for the blocked request") { browser.subresourceErrors.any { it.first == "/pagead/ad.gif" } }
        assertEquals("net::ERR_BLOCKED_BY_CLIENT", browser.subresourceErrors.first { it.first == "/pagead/ad.gif" }.second)
    }

    @Test
    fun surrogateScriptIsServedLocallyInsteadOfBlocked() {
        browser.launch("/")

        assertEquals("ok:200", fetchStatus("/js/ads.js"))
        assertFalse(browser.sawRequestWithin("/js/ads.js", 500))
    }

    @Ignore("known bug: WebView callbacks capture adBlockEnabled from the first composition, so turning ad blocking off has no effect")
    @Test
    fun turningAdBlockingOffTakesEffectAfterReload() {
        browser.launch("/")
        assertEquals("err:TypeError", fetchBody("/pagead/ad.gif"))

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Chặn Quảng cáo").performClick()
        browser.awaitPageLoaded("/")

        assertEquals("ok:ok", fetchBody("/pagead/ad.gif"))
        assertTrue("/pagead/ad.gif" in browser.requestedPaths)
    }

    @Test
    fun scriptedPopupsWithoutAGestureNeverLoad() {
        browser.launch("/")

        browser.evalJs("window.open('/popup'); 0")

        assertFalse(browser.sawRequestWithin("/popup"))
        assertEquals(browser.url("/"), browser.currentUrl())
    }

    @Test
    fun scriptedCustomSchemeNavigationIsBlocked() {
        browser.launch("/")

        browser.evalJs("location.href = 'tiktok://feed'; 0")
        browser.evalJs("location.href = 'market://details?id=app.example'; 0")

        Thread.sleep(1_000)
        assertEquals(browser.url("/"), browser.currentUrl())
    }

    @Test
    fun tappedNewWindowLinkOpensInTheSameTab() {
        browser.page("/links", LINK_PAGE.format("/opened"))
        browser.launch("/links")

        browser.tapWebViewCenter()

        browser.awaitPageLoaded("/opened")
    }

    @Test
    fun tappedNewWindowLinkToAnAdIsBlocked() {
        browser.page("/links", LINK_PAGE.format("/popads/landing"))
        browser.launch("/links")

        browser.tapWebViewCenter()

        assertFalse(browser.sawRequestWithin("/popads/landing"))
        assertEquals(browser.url("/links"), browser.currentUrl())
    }

    private companion object {
        const val LINK_PAGE = "<html><body style='margin:0'><a href='%s' target='_blank' " +
            "style='display:block;width:100vw;height:100vh'>open</a></body></html>"
    }
}
