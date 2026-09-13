package app.pwhs.blockads.ui.browser.interceptor

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import app.pwhs.blockads.ui.browser.rules.BrowserRuleDefaults
import app.pwhs.blockads.ui.browser.rules.BrowserRulePackage
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AdGuard-grade network interceptor and scriptlet manager for in-app WebView.
 * Handles fast domain/path matching, HTTP 204 responses, and scriptlet caching.
 */
object BrowserAdBlocker {

    private val scriptCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var activeHostSuffixes: Set<String> = BrowserRuleDefaults.AD_HOST_SUFFIXES.toSet()

    @Volatile
    private var activeGamblingKeywords: List<String> = BrowserRuleDefaults.GAMBLING_POPUNDER_KEYWORDS

    @Volatile
    private var activeAdPathPatterns: List<String> = BrowserRuleDefaults.AD_PATH_PATTERNS

    @Volatile
    private var activeCosmeticCss: String? = null

    @Volatile
    private var activeScriptletsJs: String? = null

    val domainsCount: Int get() = activeHostSuffixes.size
    val keywordsCount: Int get() = activeGamblingKeywords.size

    /**
     * Updates active in-memory rules from dynamic package and invalidates script caches.
     */
    fun applyRulePackage(rulePackage: BrowserRulePackage) {
        if (rulePackage.adDomains.isNotEmpty()) {
            activeHostSuffixes = rulePackage.adDomains.toSet()
        }
        if (rulePackage.gamblingKeywords.isNotEmpty()) {
            activeGamblingKeywords = rulePackage.gamblingKeywords
        }
        if (rulePackage.adPathPatterns.isNotEmpty()) {
            activeAdPathPatterns = rulePackage.adPathPatterns
        }
        if (!rulePackage.cosmeticCss.isNullOrBlank()) {
            activeCosmeticCss = rulePackage.cosmeticCss
            scriptCache.remove("adblock_cosmetic_wrapped.js")
        }
        if (!rulePackage.scriptletsJs.isNullOrBlank()) {
            activeScriptletsJs = rulePackage.scriptletsJs
            scriptCache.remove("adguard_scriptlets.js")
        }
    }

    fun shouldBlock(request: WebResourceRequest): Boolean {
        val url = request.url ?: return false
        val host = url.host?.lowercase(Locale.US) ?: return false
        val fullUrl = url.toString().lowercase(Locale.US)

        // 1. Fast host suffix match (Set contains is O(1))
        for (suffix in activeHostSuffixes) {
            if (host == suffix || host.endsWith(".$suffix")) {
                return true
            }
        }

        // 2. Gambling popunder domain keyword match
        for (keyword in activeGamblingKeywords) {
            if (host.contains(keyword)) {
                return true
            }
        }

        // 3. Specific ad path patterns match
        for (pattern in activeAdPathPatterns) {
            if (fullUrl.contains(pattern)) {
                return true
            }
        }

        return false
    }

    /**
     * Evaluates whether an outgoing page navigation should be blocked.
     * Blocks known ad/gambling/tracking URLs according to active rules.
     */
    fun shouldBlockNavigation(request: WebResourceRequest, currentUrl: String?): Boolean {
        return shouldBlock(request)
    }

    /**
     * Creates a blocked response that aborts the network connection with net::ERR_BLOCKED_BY_CLIENT,
     * ensuring JavaScript fetch/XHR and test suites properly detect the network block.
     */
    fun createBlockedResponse(): WebResourceResponse {
        val errorStream = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("ERR_BLOCKED_BY_CLIENT")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("ERR_BLOCKED_BY_CLIENT")
        }
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            errorStream
        )
    }

    /**
     * Creates AdGuard-style mock stub for in-player video ad dependencies,
     * preventing players from getting trapped in infinite wait loops.
     */
    fun getMockInplayerAdxResponse(): WebResourceResponse {
        val mockJs = """
            window.show_adx = 0;
            window.COUNT_VAST = 0;
            window.vastAdx = [];
            window.bannerAdx = [];
            window.funcGetvastAdx = function() { return []; };
            window.funcJWonReadyVAST = function() {};
            window.bannerAdxAllowed = function() { return false; };
            window.hideInplayerBanner = function() {};
        """.trimIndent()
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Content-Type" to "application/javascript; charset=UTF-8"
        )
        return WebResourceResponse(
            "application/javascript",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(mockJs.toByteArray(Charsets.UTF_8))
        )
    }

    private val TRANSPARENT_1X1_GIF = android.util.Base64.decode(
        "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7",
        android.util.Base64.DEFAULT
    )

    /**
     * Checks if a request requires an AdGuard-style surrogate response (stub JS or 1x1 pixel)
     * instead of outright blocking, to defeat anti-adblock detection scripts.
     */
    fun getSurrogateResponse(fullUrl: String): WebResourceResponse? {
        return when {
            fullUrl.contains("inplayer-adx") || fullUrl.contains("player-adx") -> {
                getMockInplayerAdxResponse()
            }
            fullUrl.contains("adsbygoogle.js") -> {
                getMockAdsByGoogleResponse()
            }
            fullUrl.contains("/ads.js") || fullUrl.contains("/banner-ads.js") -> {
                getMockEmptyJsResponse()
            }
            fullUrl.contains("google-analytics.com/collect") || fullUrl.contains("/collect?v=") -> {
                getTransparentPixelResponse()
            }
            else -> null
        }
    }

    private fun getMockEmptyJsResponse(): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Content-Type" to "application/javascript; charset=UTF-8"
        )
        return WebResourceResponse(
            "application/javascript",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun getMockAdsByGoogleResponse(): WebResourceResponse {
        val mockJs = """
            (function() {
                window.adsbygoogle = window.adsbygoogle || [];
                window.adsbygoogle.loaded = true;
                window.adsbygoogle.push = function() {};
            })();
        """.trimIndent()
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Content-Type" to "application/javascript; charset=UTF-8"
        )
        return WebResourceResponse(
            "application/javascript",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(mockJs.toByteArray(Charsets.UTF_8))
        )
    }

    private fun getTransparentPixelResponse(): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Content-Type" to "image/gif"
        )
        return WebResourceResponse(
            "image/gif",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(TRANSPARENT_1X1_GIF)
        )
    }

    /**
     * AdGuard User-Agent spoofing: Eliminates "Version/4.0" and "; wv" WebView tokens.
     */
    fun spoofChromeUserAgent(defaultUa: String): String {
        val chromeVersionMatch = Regex("Chrome/(\\d+)").find(defaultUa)
        val chromeVersion = chromeVersionMatch?.groupValues?.getOrNull(1) ?: "128"
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion.0.0.0 Mobile Safari/537.36"
    }

    fun getServiceWorkerKillerScript(context: Context): String {
        return scriptCache.getOrPut("service_worker_killer.js") {
            readAsset(context, "browser/service_worker_killer.js")
        }
    }

    fun getYoutubeSanitizerScript(context: Context): String {
        return scriptCache.getOrPut("youtube_sanitizer.js") {
            readAsset(context, "browser/youtube_sanitizer.js")
        }
    }

    fun getAdguardScriptlets(context: Context): String {
        return activeScriptletsJs?.takeIf { it.isNotBlank() }
            ?: scriptCache.getOrPut("adguard_scriptlets.js") {
                readAsset(context, "browser/adguard_scriptlets.js")
            }
    }

    fun getBackgroundPlayScript(context: Context): String {
        return scriptCache.getOrPut("background_play.js") {
            readAsset(context, "browser/background_play.js")
        }
    }

    fun getCosmeticCssScript(context: Context): String {
        return scriptCache.getOrPut("adblock_cosmetic.css") {
            val rawCss = (activeCosmeticCss?.takeIf { it.isNotBlank() }
                ?: readAsset(context, "browser/adblock_cosmetic.css"))
                .replace("\\", "\\\\")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\"", "\\\"")
            """
            (function() {
                function inject() {
                    if (document.getElementById('__blockads_cosmetic_style')) return;
                    var target = document.head || document.documentElement;
                    if (!target) {
                        requestAnimationFrame(inject);
                        return;
                    }
                    var style = document.createElement('style');
                    style.id = '__blockads_cosmetic_style';
                    style.textContent = "$rawCss";
                    target.appendChild(style);
                }
                inject();
            })();
            """.trimIndent()
        }
    }

    fun sanitizeSearchUrl(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=" + Uri.encode(trimmed)
        }
    }

    fun injectEarlyScripts(context: Context, view: WebView?, url: String?) {
        // 1. Cosmetic CSS injected early so layout never renders ad gaps
        val cssScript = getCosmeticCssScript(context)
        if (cssScript.isNotEmpty()) view?.evaluateJavascript(cssScript, null)

        // 2. AdGuard Scriptlets (anti-adblock, synthetic clicks, overlays)
        val scriptlets = getAdguardScriptlets(context)
        if (scriptlets.isNotEmpty()) view?.evaluateJavascript(scriptlets, null)

        // 3. Kill Service Workers
        val swScript = getServiceWorkerKillerScript(context)
        if (swScript.isNotEmpty()) view?.evaluateJavascript(swScript, null)

        // 4. Background Playback
        val bgPlay = getBackgroundPlayScript(context)
        if (bgPlay.isNotEmpty()) view?.evaluateJavascript(bgPlay, null)

        // 5. YouTube Sanitizer
        if (url?.contains("youtube.com") == true) {
            val ytScript = getYoutubeSanitizerScript(context)
            if (ytScript.isNotEmpty()) view?.evaluateJavascript(ytScript, null)
        }
    }

    fun injectLateScripts(context: Context, view: WebView?, url: String?) {
        val cssScript = getCosmeticCssScript(context)
        if (cssScript.isNotEmpty()) view?.evaluateJavascript(cssScript, null)

        val scriptlets = getAdguardScriptlets(context)
        if (scriptlets.isNotEmpty()) view?.evaluateJavascript(scriptlets, null)

        val bgPlay = getBackgroundPlayScript(context)
        if (bgPlay.isNotEmpty()) view?.evaluateJavascript(bgPlay, null)

        if (url?.contains("youtube.com") == true) {
            val ytScript = getYoutubeSanitizerScript(context)
            if (ytScript.isNotEmpty()) view?.evaluateJavascript(ytScript, null)
        }
    }

    private fun readAsset(context: Context, filename: String): String {
        return runCatching {
            context.assets.open(filename).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
}
