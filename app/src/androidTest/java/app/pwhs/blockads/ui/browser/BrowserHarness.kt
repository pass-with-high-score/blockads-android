package app.pwhs.blockads.ui.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.pwhs.blockads.waitUntil
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONTokener
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Serves local pages to the in-app browser and drives its WebView from the test thread.
 *
 * The app forbids cleartext, so pages are served over HTTPS with a throwaway certificate. The app's
 * WebViewClient is wrapped by one that forwards every callback the app overrides and additionally
 * accepts that certificate and records subresource errors.
 */
@RequiresApi(Build.VERSION_CODES.O)
class BrowserHarness : AutoCloseable {

    val server = MockWebServer()
    private val pages = mutableMapOf<String, MockResponse>()
    val requestedPaths = CopyOnWriteArrayList<String>()
    val subresourceErrors = CopyOnWriteArrayList<Pair<String, String>>()
    private var activity: BrowserActivity? = null

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                requestedPaths += path
                return pages[path.substringBefore('?')]?.clone() ?: MockResponse().setBody("ok")
            }
        }
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(LOCALHOST).build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(tls.sslSocketFactory(), false)
        server.start(InetAddress.getByName(LOCALHOST), 0)
    }

    fun page(path: String, html: String, vararg headers: Pair<String, String>) {
        pages[path] = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(html).apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
        }
    }

    fun url(path: String): String = server.url(path).toString()

    /** Opens the browser on [path]; the first load fails the TLS check until the test client is installed. */
    fun launch(path: String) {
        val intent = BrowserActivity.createIntent(context, url(path)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<BrowserActivity>(intent).onActivity { activity = it }
        installTestClient()
        load(path)
    }

    fun load(path: String) {
        val view = webView()
        onMain { view.loadUrl(url(path)) }
        awaitPageLoaded(path)
    }

    /**
     * Finishes the browser directly. ActivityScenario.close() would background it first, which enters PiP, and
     * it stops tracking the activity once onNewIntent replaces the launch intent.
     */
    fun closeActivity() {
        val current = activity ?: return
        onMain { current.finish() }
        waitUntil("the browser to be destroyed") { current.isDestroyed }
        activity = null
    }

    fun webView(): WebView {
        var found: WebView? = null
        val current = checkNotNull(activity) { "browser not launched" }
        waitUntil("WebView to be attached") {
            onMain { found = findWebView(current.window.decorView) }
            found != null
        }
        return found!!
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }

    fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    fun currentUrl(): String? {
        var url: String? = null
        val view = webView()
        onMain { url = view.url }
        return url
    }

    fun awaitPageLoaded(path: String) {
        val view = webView()
        waitUntil("page $path to load") {
            var done = false
            onMain { done = view.url == url(path) && view.progress == 100 }
            done && evalJs("document.readyState") == "complete"
        }
    }

    /** Evaluates [script] in the page and returns its result decoded from JSON (strings unquoted). */
    fun evalJs(script: String): String? {
        val view = webView()
        val latch = CountDownLatch(1)
        var raw: String? = null
        onMain { view.evaluateJavascript(script) { raw = it; latch.countDown() } }
        check(latch.await(10, TimeUnit.SECONDS)) { "evaluateJavascript timed out: $script" }
        return raw?.let { JSONTokener(it).nextValue() }?.takeUnless { it == org.json.JSONObject.NULL }?.toString()
    }

    /** Runs async [js] that must assign `window.__result`, then waits for it. */
    fun evalAsync(js: String, timeoutMs: Long = 10_000): String {
        evalJs("window.__result = undefined; $js; 0")
        var result: String? = null
        waitUntil("window.__result from: $js", timeoutMs) {
            result = evalJs("window.__result === undefined ? null : String(window.__result)")
            result != null
        }
        return result!!
    }

    private fun installTestClient() {
        val view = webView()
        waitUntil("the initial load to settle") {
            var settled = false
            onMain { settled = view.progress == 100 }
            settled
        }
        SystemClock.sleep(500)
        onMain {
            val app = view.webViewClient
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? =
                    app.shouldInterceptRequest(v, r)

                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean =
                    app.shouldOverrideUrlLoading(v, r)

                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) = app.onPageStarted(v, url, favicon)

                override fun onPageFinished(v: WebView?, url: String?) = app.onPageFinished(v, url)

                override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                    subresourceErrors += (r?.url?.path ?: "") to (e?.description?.toString() ?: "")
                    app.onReceivedError(v, r, e)
                }

                override fun onReceivedSslError(v: WebView?, handler: SslErrorHandler, error: SslError) {
                    if (Uri.parse(error.url).host == LOCALHOST) handler.proceed() else handler.cancel()
                }
            }
        }
    }

    /** Taps through the input system, so the page sees a real user gesture. */
    fun tapWebViewCenter() {
        val view = webView()
        val location = IntArray(2)
        onMain { view.getLocationOnScreen(location) }
        val x = location[0] + view.width / 2
        val y = location[1] + view.height / 2
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input tap $x $y").close()
    }

    /** Sends a new intent to the running singleTask browser, as another app could. */
    fun sendIntent(intent: Intent) {
        context.startActivity(intent.setClass(context, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun awaitRequest(path: String, timeoutMs: Long = 10_000) = waitUntil("request to $path", timeoutMs) { path in requestedPaths }

    /** Gives a request that should not happen time to arrive, then reports whether it did. */
    fun sawRequestWithin(path: String, timeoutMs: Long = 3_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (path in requestedPaths) return true
            SystemClock.sleep(100)
        }
        return path in requestedPaths
    }

    override fun close() {
        closeActivity()
        server.shutdown()
    }
}

private const val LOCALHOST = "localhost"
