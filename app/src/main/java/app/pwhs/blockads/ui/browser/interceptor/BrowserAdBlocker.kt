package app.pwhs.blockads.ui.browser.interceptor

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AdGuard-grade network interceptor and scriptlet manager for in-app WebView.
 * Handles fast domain/path matching, HTTP 204 responses, and scriptlet caching.
 */
object BrowserAdBlocker {

    private val scriptCache = ConcurrentHashMap<String, String>()

    private val AD_HOST_SUFFIXES = listOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "adsterra.com",
        "exoclick.com",
        "ezoic.net",
        "ezoic.com",
        "mgid.com",
        "clickadu.com",
        "revenuehits.com",
        "bidvertiser.com",
        "hilltopads.net",
        "scorecardresearch.com",
        "zedo.com",
        "admob.com",
        "ads.youtube.com",
        "advertising.com",
        "rubiconproject.com",
        "pubmatic.com",
        "casalemedia.com",
        "openx.net",
        "adroll.com",
        "smartadserver.com",
        "moatads.com",
        "quantserve.com",
        "serving-sys.com",
        "fls-na.amazon.com",
        "fls-eu.amazon.com",
        // Regional Vietnamese ad networks
        "admicro.vn",
        "vcmedia.vn",
        "eclick.vn",
        "adtima.vn",
        "novanet.vn",
        // Popunder, 18+ and streaming ad networks (AdGuard filter sets)
        "adxcontent.com",
        "adxmedia.com",
        "vlit.site",
        "vlit.xyz",
        "exosrv.com",
        "tsyndicate.com",
        "tsyndication.com",
        "realsrv.com",
        "trafficjunky.com",
        "trafficstars.com",
        "ero-advertising.com",
        "juicyads.com",
        "hilltopads.com",
        "ad-maven.com",
        "adcash.com",
        "monetag.com",
        "yepads.com",
        "richpush.co",
        "richads.com",
        "clarium.io",
        // Fake video ads & ad network redirectors
        "clumsy-whereas.com",
        "ttwstatic.com",
        "bytedapm.com"
    )

    private val GAMBLING_POPUNDER_KEYWORDS = listOf(
        "lu88", "hbet", "vu88", "man88", "k88.", "tx88", "du88", "x1bet",
        "bet88", "kubet", "shbet", "789bet", "okvip", "jun88", "hi88",
        "f8bet", "mb66", "123b", "fun88", "bk8"
    )

    private val AD_PATH_PATTERNS = listOf(
        "/ads.js",
        "/pagead/",
        "/doubleclick/",
        "/ad_status",
        "/get_midroll_info",
        "/api/stats/ads",
        "/youtubei/v1/player/ad_break",
        "googletagservices.com/tag/js/gpt.js",
        "/static/doubleclick/instream",
        "/popunder",
        "/popads",
        "/advertisement",
        "/adserver",
        "-adx.js",
        "/vl-top-adx",
        "/vl-main-adx",
        "/vl-underplayer-adx",
        "/vl-native-adx",
        "/catfish"
    )

    fun shouldBlock(request: WebResourceRequest): Boolean {
        val url = request.url ?: return false
        val host = url.host?.lowercase(Locale.US) ?: return false
        val fullUrl = url.toString().lowercase(Locale.US)

        // 1. Fast host suffix match
        for (suffix in AD_HOST_SUFFIXES) {
            if (host == suffix || host.endsWith(".$suffix")) {
                return true
            }
        }

        // 2. Gambling popunder domain keyword match
        for (keyword in GAMBLING_POPUNDER_KEYWORDS) {
            if (host.contains(keyword)) {
                return true
            }
        }

        // 3. Specific ad path patterns match
        for (pattern in AD_PATH_PATTERNS) {
            if (fullUrl.contains(pattern)) {
                return true
            }
        }

        return false
    }

    /**
     * Creates HTTP 204 No Content response to stop client retry loops.
     */
    fun createBlockedResponse(): WebResourceResponse {
        val headers = mapOf(
            "Cache-Control" to "no-store, no-cache, must-revalidate",
            "Pragma" to "no-cache"
        )
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            headers,
            ByteArrayInputStream(ByteArray(0))
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
            fullUrl.contains("google-analytics.com/collect") || fullUrl.contains("/collect?v=") -> {
                getTransparentPixelResponse()
            }
            else -> null
        }
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
        return scriptCache.getOrPut("adguard_scriptlets.js") {
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
            val rawCss = readAsset(context, "browser/adblock_cosmetic.css")
                .replace("\n", " ")
                .replace("\"", "\\\"")
            """
            (function() {
                if (window.__blockads_css_injected) return;
                window.__blockads_css_injected = true;
                var style = document.createElement('style');
                style.textContent = "$rawCss";
                (document.head || document.documentElement).appendChild(style);
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
