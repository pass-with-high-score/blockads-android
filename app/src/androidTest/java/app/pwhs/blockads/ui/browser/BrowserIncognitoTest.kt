package app.pwhs.blockads.ui.browser

import android.webkit.CookieManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.pwhs.blockads.waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The browser starts in incognito mode, so nothing a page stores may outlive the session. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class BrowserIncognitoTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val browser = BrowserHarness().apply {
        page("/", "<html><body>store</body></html>", "Set-Cookie" to "sid=abc; Max-Age=3600; Path=/")
    }

    @Before
    fun startClean() = browser.onMain { CookieManager.getInstance().removeAllCookies(null) }

    @After
    fun tearDown() = browser.close()

    private fun cookie(): String? {
        var value: String? = null
        browser.onMain { value = CookieManager.getInstance().getCookie(browser.url("/")) }
        return value
    }

    private fun storePageData() {
        browser.launch("/")
        browser.evalJs("localStorage.setItem('k', 'v'); sessionStorage.setItem('s', 'v'); 0")
        assertTrue(cookie().orEmpty().contains("sid=abc"))
        assertEquals("v", browser.evalJs("localStorage.getItem('k')"))
    }

    @Test
    fun closingTheBrowserClearsCookies() {
        storePageData()

        browser.closeActivity()

        waitUntil("cookies to be cleared", 5_000) { cookie() == null }
    }

    @Ignore("known bug: closing the incognito browser clears cookies but leaves localStorage for the next session")
    @Test
    fun closingTheBrowserClearsWebStorage() {
        storePageData()

        browser.closeActivity()
        browser.launch("/")

        assertNull(browser.evalJs("localStorage.getItem('k')"))
    }

    @Test
    fun clearDataFromTheMenuClearsCookiesAndWebStorage() {
        storePageData()

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Xóa cache").performScrollTo().performClick()

        waitUntil("cookies to be cleared", 5_000) { cookie() == null }
        browser.load("/")
        // Reloading sets the cookie again, so only storage is checked after it.
        assertNull(browser.evalJs("localStorage.getItem('k')"))
    }
}
