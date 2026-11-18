package app.pwhs.blockads.ui.browser.interceptor

import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.pwhs.blockads.data.logging.HttpDebugLogStore
import app.pwhs.blockads.data.logging.HttpLogAction
import java.util.Locale

/**
 * Records In-App Browser network requests and JS scriptlet actions into [HttpDebugLogStore].
 */
object BrowserRequestLogger {

    fun interceptAndLog(
        request: WebResourceRequest?,
        adBlockEnabled: Boolean,
        onAdBlocked: () -> Unit
    ): WebResourceResponse? {
        if (request == null) return null
        if (adBlockEnabled) {
            val fullUrl = request.url?.toString()?.lowercase(Locale.US) ?: ""
            val surrogate = BrowserAdBlocker.getSurrogateResponse(fullUrl)
            if (surrogate != null) {
                logRequest(request, HttpLogAction.SURROGATE, "surrogate")
                onAdBlocked()
                return surrogate
            }
            val match = BrowserAdBlocker.matchBlockRule(request)
            if (match.blocked) {
                logRequest(request, HttpLogAction.BLOCKED, match.rule)
                onAdBlocked()
                return BrowserAdBlocker.createBlockedResponse()
            }
        }
        logRequest(request, HttpLogAction.ALLOWED, "")
        return null
    }

    fun logRequest(request: WebResourceRequest, action: HttpLogAction, rule: String) {
        val urlStr = request.url?.toString() ?: return
        val headers = request.requestHeaders ?: emptyMap()
        val referer = headers["Referer"] ?: headers["referer"] ?: headers["Origin"] ?: headers["origin"]
        val initiator = referer?.let { runCatching { Uri.parse(it).host }.getOrNull() }
            ?: request.url?.host.orEmpty()
        HttpDebugLogStore.record(
            source = "Browser",
            action = action,
            rule = rule,
            method = request.method ?: "GET",
            resourceType = inferResourceType(request, headers, urlStr),
            initiator = initiator.lowercase(Locale.US),
            url = urlStr
        )
    }

    private fun inferResourceType(
        request: WebResourceRequest,
        headers: Map<String, String>,
        urlStr: String
    ): String {
        if (request.isForMainFrame) return "document"
        val dest = (headers["Sec-Fetch-Dest"] ?: headers["sec-fetch-dest"])?.lowercase(Locale.US)
        if (!dest.isNullOrBlank()) {
            return when (dest) {
                "iframe" -> "frame"
                "empty" -> "xhr"
                "style" -> "stylesheet"
                else -> dest
            }
        }
        val lower = urlStr.lowercase(Locale.US)
        return when {
            lower.contains(".js") -> "script"
            lower.contains(".css") -> "stylesheet"
            lower.contains(".png") || lower.contains(".jpg") || lower.contains(".webp") || lower.contains(".gif") -> "image"
            else -> "other"
        }
    }

    class JsLoggerBridge {
        @android.annotation.SuppressLint("JavascriptInterface")
        @JavascriptInterface
        fun log(rule: String, url: String, detail: String) {
            val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().lowercase(Locale.US)
            HttpDebugLogStore.record(
                source = "Browser",
                action = HttpLogAction.SCRIPTLET,
                rule = rule,
                method = "JS",
                resourceType = "scriptlet",
                initiator = host,
                url = url,
                detail = detail
            )
        }
    }
}
