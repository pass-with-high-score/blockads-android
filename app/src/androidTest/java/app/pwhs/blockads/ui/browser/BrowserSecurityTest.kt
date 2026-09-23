package app.pwhs.blockads.ui.browser

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.pwhs.blockads.data.dao.ElementRuleDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** What an untrusted page or another app can make the browser do. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class BrowserSecurityTest {

    private val browser = BrowserHarness().apply { page("/", "<html><body>untrusted</body></html>") }
    private val elementRules: ElementRuleDao = getKoin().get()

    @After
    fun tearDown() {
        browser.close()
        runBlocking { elementRules.deleteAllForDomain(VICTIM) }
    }

    @Ignore("known bug: the element-picker bridge is attached to every page, so any page can persist hiding rules for any domain")
    @Test
    fun untrustedPageCannotPersistElementRules() {
        browser.launch("/")

        browser.evalJs("try { blockadsPickerProxy.onPickerCompleted('body', '$VICTIM') } catch (e) {}; 0")

        Thread.sleep(1_000)
        assertEquals(emptyList<String>(), runBlocking { elementRules.getSelectorsForDomain(VICTIM) })
    }

    @Ignore("known bug: the exported singleTask browser runs a javascript: URL from another app's EXTRA_URL in the open page")
    @Test
    fun javascriptUrlFromAnotherAppDoesNotRunInTheOpenPage() {
        browser.launch("/")

        // sendBeacon outlives the navigation the browser starts right after running the URL.
        browser.sendIntent(Intent().putExtra(BrowserActivity.EXTRA_URL, "javascript:void(navigator.sendBeacon('/pwned-extra'))"))
        assertFalse(browser.sawRequestWithin("/pwned-extra"))
    }

    @Ignore("known bug: the exported singleTask browser runs a javascript: URL from another app's intent data in the open page")
    @Test
    fun javascriptDataUriFromAnotherAppDoesNotRunInTheOpenPage() {
        browser.launch("/")

        browser.sendIntent(Intent(Intent.ACTION_VIEW, Uri.parse("javascript:void(navigator.sendBeacon('/pwned-data'))")))
        assertFalse(browser.sawRequestWithin("/pwned-data"))
    }

    @Test
    fun httpsUrlFromAnotherAppReplacesTheOpenPage() {
        browser.page("/next", "<html><body>next</body></html>")
        browser.launch("/")

        browser.sendIntent(Intent().putExtra(BrowserActivity.EXTRA_URL, browser.url("/next")))

        browser.awaitPageLoaded("/next")
    }

    private companion object {
        const val VICTIM = "victim.example"
    }
}
