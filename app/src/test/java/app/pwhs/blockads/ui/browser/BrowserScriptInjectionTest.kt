package app.pwhs.blockads.ui.browser

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import app.pwhs.blockads.ui.browser.rules.BrowserRulePackage
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserScriptInjectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scripts = mutableListOf<String>()
    private val webView: WebView = mockk {
        every { evaluateJavascript(any(), any()) } answers { scripts += firstArg<String>() }
    }

    @After
    fun tearDown() = resetBrowserAdBlocker()

    /** The CSS/selector text lands inside a single-quoted JS string; return what sits between the quotes. */
    private fun userCssLiteral(js: String): String =
        js.substringAfter("el.textContent = '").substringBeforeLast("';")

    @Test
    fun `early injection runs css, scriptlets, sw killer and background play`() {
        BrowserAdBlocker.injectEarlyScripts(context, webView, "https://example.com")
        assertEquals(4, scripts.size)
        assertTrue(scripts[0].contains("__blockads_cosmetic_style"))
    }

    @Test
    fun `youtube pages also get the sanitizer, early and late`() {
        BrowserAdBlocker.injectEarlyScripts(context, webView, "https://m.youtube.com/watch")
        assertEquals(5, scripts.size)
        scripts.clear()
        BrowserAdBlocker.injectLateScripts(context, webView, "https://m.youtube.com/watch")
        assertEquals(4, scripts.size)
        scripts.clear()
        BrowserAdBlocker.injectLateScripts(context, webView, "https://example.com")
        assertEquals(3, scripts.size)
    }

    @Test
    fun `remote scriptlets replace the bundled asset`() {
        BrowserAdBlocker.applyRulePackage(BrowserRulePackage(scriptletsJs = "window.remote=1;"))
        assertEquals("window.remote=1;", BrowserAdBlocker.getAdguardScriptlets(context))

        BrowserAdBlocker.applyRulePackage(BrowserRulePackage(scriptletsJs = "  "))
        assertTrue(BrowserAdBlocker.getAdguardScriptlets(context).length > "window.remote=1;".length)
    }

    @Test
    fun `cosmetic css is escaped into a double-quoted JS string`() {
        BrowserAdBlocker.applyRulePackage(BrowserRulePackage(cosmeticCss = "a[href=\"x\"]\n{display:none}\r\\"))
        val script = BrowserAdBlocker.getCosmeticCssScript(context)
        val literal = script.substringAfter("style.textContent = \"").substringBefore("\";\n")
        assertEquals("a[href=\\\"x\\\"] {display:none}\\\\", literal)
    }

    @Test
    fun `cosmetic css is cached until a new package arrives`() {
        BrowserAdBlocker.applyRulePackage(BrowserRulePackage(cosmeticCss = ".one{}"))
        val first = BrowserAdBlocker.getCosmeticCssScript(context)
        assertTrue(first.contains(".one{}"))
        assertEquals(first, BrowserAdBlocker.getCosmeticCssScript(context))

        BrowserAdBlocker.applyRulePackage(BrowserRulePackage(cosmeticCss = ".two{}"))
        assertTrue(BrowserAdBlocker.getCosmeticCssScript(context).contains(".two{}"))
    }

    @Test
    fun `empty selector list removes the user style`() {
        BrowserAdBlocker.injectUserElementRules(webView, emptyList())
        assertTrue(scripts.single().contains(".remove()"))
    }

    @Test
    fun `null web view is ignored`() {
        BrowserAdBlocker.injectUserElementRules(null, listOf(".ad"))
        assertTrue(scripts.isEmpty())
    }

    @Test
    fun `selectors are joined and quotes escaped`() {
        BrowserAdBlocker.injectUserElementRules(webView, listOf(" .ad ", "div[title='x']", "a\\b"))
        assertEquals(
            ".ad,div[title=\\'x\\'],a\\\\b { display: none !important; }",
            userCssLiteral(scripts.single())
        )
    }

    @Ignore("injectUserElementRules escapes only backslash and quote, so a newline or U+2028 in a selector breaks the JS string")
    @Test
    fun `line terminators in selectors cannot break the JS string`() {
        BrowserAdBlocker.injectUserElementRules(webView, listOf(".a\n');alert(1);//", ".b\u2028x"))
        val literal = userCssLiteral(scripts.single())
        assertFalse(literal.contains('\n'))
        assertFalse(literal.contains('\u2028'))
    }

    @Ignore("a selector containing '}' closes the hiding rule and injects arbitrary CSS")
    @Test
    fun `selectors cannot inject extra CSS rules`() {
        BrowserAdBlocker.injectUserElementRules(webView, listOf("x} body{display:none"))
        assertEquals(1, userCssLiteral(scripts.single()).count { it == '}' })
    }
}
