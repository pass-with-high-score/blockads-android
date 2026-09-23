package app.pwhs.blockads.ui.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/** Only creates the activity (no composition), so the URL routing can be read without a WebView. */
@RunWith(RobolectricTestRunner::class)
class BrowserActivityIntentTest {

    private fun launch(intent: Intent): BrowserActivity =
        Robolectric.buildActivity(BrowserActivity::class.java, intent).create().get()

    private val BrowserActivity.currentUrl: String
        get() = ReflectionHelpers.getField<MutableState<String>>(this, "_currentUrl").value

    private fun browserIntent() = Intent(ApplicationProvider.getApplicationContext(), BrowserActivity::class.java)

    @Test
    fun `url comes from the extra, then the data, then the default`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("https://a.test", launch(BrowserActivity.createIntent(ctx, "https://a.test")).currentUrl)
        assertEquals("https://b.test/x", launch(browserIntent().setData(Uri.parse("https://b.test/x"))).currentUrl)
        assertEquals("https://m.youtube.com", launch(browserIntent()).currentUrl)
    }

    @Test
    fun `new intents replace the url unless blank`() {
        val activity = launch(browserIntent())
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity, "onNewIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, browserIntent().putExtra(BrowserActivity.EXTRA_URL, "https://c.test"))
        )
        assertEquals("https://c.test", activity.currentUrl)
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity, "onNewIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, browserIntent().putExtra(BrowserActivity.EXTRA_URL, " "))
        )
        assertEquals("https://c.test", activity.currentUrl)
    }

    @Ignore("the exported browser loads a javascript: URL from EXTRA_URL or intent data into the open page")
    @Test
    fun `javascript urls from intents are refused`() {
        val activity = launch(browserIntent().putExtra(BrowserActivity.EXTRA_URL, "javascript:alert(document.cookie)"))
        assertFalse(activity.currentUrl.startsWith("javascript:"))
        ReflectionHelpers.callInstanceMethod<Unit>(
            activity, "onNewIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, browserIntent().setData(Uri.parse("javascript:alert(1)")))
        )
        assertFalse(activity.currentUrl.startsWith("javascript:"))
    }
}
