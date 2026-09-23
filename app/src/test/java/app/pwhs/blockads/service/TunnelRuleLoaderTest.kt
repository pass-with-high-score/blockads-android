package app.pwhs.blockads.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.ui.browser.rules.BrowserRuleDefaults
import app.pwhs.blockads.ui.browser.rules.BrowserRulePackage
import app.pwhs.blockads.ui.browser.rules.BrowserRuleStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TunnelRuleLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun asset(name: String) = context.assets.open("browser/$name").bufferedReader().use { it.readText() }

    private fun cache(pkg: BrowserRulePackage) {
        BrowserRuleStorage(context).savePackage(pkg.copy(version = BrowserRuleDefaults.INITIAL_VERSION + 1))
    }

    @Test
    fun `without a cache the bundled css and patterns are used`() {
        assertEquals(asset("adblock_cosmetic.css").trim(), TunnelRuleLoader.loadCosmeticCss(context))
        assertEquals(BrowserRuleDefaults.AD_PATH_PATTERNS.joinToString("\n"), TunnelRuleLoader.loadAdPathPatterns(context))
    }

    @Test
    fun `cached custom css and patterns win`() {
        cache(BrowserRulePackage(cosmeticCss = "  .x{display:none}  ", adPathPatterns = listOf("/a", "/b")))
        assertEquals(".x{display:none}", TunnelRuleLoader.loadCosmeticCss(context))
        assertEquals("/a\n/b", TunnelRuleLoader.loadAdPathPatterns(context))
    }

    @Test
    fun `unresolved URLs and blanks fall back to bundled assets`() {
        cache(BrowserRulePackage(cosmeticCss = "https://cdn.test/a.css", scriptletsJs = "http://cdn.test/s.js", adPathPatterns = emptyList()))
        assertEquals(asset("adblock_cosmetic.css"), TunnelRuleLoader.loadCosmeticCss(context))
        assertEquals(BrowserRuleDefaults.AD_PATH_PATTERNS.joinToString("\n"), TunnelRuleLoader.loadAdPathPatterns(context))
        assertTrue(TunnelRuleLoader.loadScriptletsJs(context).contains(asset("adguard_scriptlets.js")))
    }

    @Test
    fun `scriptlets bundle the service worker killer, base scriptlets and youtube sanitizer in order`() {
        cache(BrowserRulePackage(scriptletsJs = "window.base=1;"))
        val js = TunnelRuleLoader.loadScriptletsJs(context)
        assertTrue(js.startsWith(asset("service_worker_killer.js")))
        assertTrue(js.endsWith(asset("youtube_sanitizer.js")))
        assertTrue(js.contains("\n\nwindow.base=1;\n\n"))
    }
}
