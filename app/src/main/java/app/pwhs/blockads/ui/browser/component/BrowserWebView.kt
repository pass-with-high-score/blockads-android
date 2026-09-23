package app.pwhs.blockads.ui.browser.component

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.pwhs.blockads.ui.browser.BrowserUiIntent
import app.pwhs.blockads.ui.browser.BrowserUiState
import app.pwhs.blockads.ui.browser.extractFileName
import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import app.pwhs.blockads.ui.browser.picker.ElementPickerBridge
import timber.log.Timber
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebView(
    uiState: BrowserUiState,
    initialUrl: String,
    onIntent: (BrowserUiIntent) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onPullRefresh: () -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            PullRefreshWebView(ctx).apply {
                onPullToRefreshTrigger = onPullRefresh
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    @Suppress("DEPRECATION")
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                    val defaultUa = userAgentString
                    userAgentString = BrowserAdBlocker.spoofChromeUserAgent(defaultUa)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        isAlgorithmicDarkeningAllowed = true
                    }
                }

                val webView = this
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, true)
                }

                // Register element picker bridge for Aloha-style element blocking
                addJavascriptInterface(
                    ElementPickerBridge(
                        onRulePicked = { selector, pickedDomain ->
                            val currentDomain = if (pickedDomain.isNotBlank()) {
                                pickedDomain
                            } else {
                                Uri.parse(url ?: "").host ?: ""
                            }
                            if (currentDomain.isNotBlank()) {
                                onIntent(BrowserUiIntent.ElementRulePicked(selector, currentDomain))
                            }
                        },
                        onDismissed = {
                            onIntent(BrowserUiIntent.DeactivateElementPicker)
                        }
                    ),
                    "blockadsPickerProxy"
                )

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (request != null && uiState.adBlockEnabled) {
                            val fullUrl = request.url?.toString()?.lowercase(Locale.US) ?: ""
                            val surrogate = BrowserAdBlocker.getSurrogateResponse(fullUrl)
                            if (surrogate != null) {
                                onIntent(BrowserUiIntent.AdBlocked)
                                return surrogate
                            }
                            if (BrowserAdBlocker.shouldBlock(request)) {
                                onIntent(BrowserUiIntent.AdBlocked)
                                return BrowserAdBlocker.createBlockedResponse()
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        if (uiState.isElementPickerActive) return true
                        val reqUrl = request?.url ?: return false
                        val scheme = reqUrl.scheme?.lowercase(Locale.US) ?: return false

                        if (scheme != "http" && scheme != "https") {
                            val blockedSchemes = listOf("snssdk", "tiktok", "musically", "shopee", "lazada")
                            if (blockedSchemes.any { scheme.startsWith(it) }) return true
                            if (request.hasGesture().not()) return true
                            return runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, reqUrl))
                                true
                            }.getOrDefault(true)
                        }

                        if (uiState.adBlockEnabled && BrowserAdBlocker.shouldBlockNavigation(request, view?.url)) {
                            onIntent(BrowserUiIntent.AdBlocked)
                            return true
                        }

                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { onIntent(BrowserUiIntent.PageStarted(it)) }
                        if (uiState.adBlockEnabled) {
                            BrowserAdBlocker.injectEarlyScripts(context, view, url)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val currentTitle = view?.title ?: ""
                        url?.let { onIntent(BrowserUiIntent.PageFinished(it, currentTitle)) }
                        if (uiState.adBlockEnabled) {
                            BrowserAdBlocker.injectLateScripts(context, view, url)
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        if (uiState.isElementPickerActive) return false
                        if (uiState.popupBlockEnabled && !isUserGesture) {
                            onIntent(BrowserUiIntent.AdBlocked)
                            return false
                        }
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        val tempWebView = WebView(view?.context ?: return false)
                        tempWebView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val targetUrl = request?.url?.toString() ?: return false
                                if (uiState.adBlockEnabled && (BrowserAdBlocker.shouldBlock(request) || BrowserAdBlocker.shouldBlockNavigation(request, view?.url))) {
                                    onIntent(BrowserUiIntent.AdBlocked)
                                    return true
                                }
                                if (uiState.popupBlockEnabled && isBlockedPopupUrl(targetUrl)) {
                                    onIntent(BrowserUiIntent.AdBlocked)
                                    return true
                                }
                                onIntent(BrowserUiIntent.LoadUrl(targetUrl))
                                return true
                            }
                        }
                        transport.webView = tempWebView
                        resultMsg.sendToTarget()
                        return true
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        onIntent(BrowserUiIntent.UpdateProgress(newProgress))
                        if (newProgress in 15..25 && uiState.adBlockEnabled) {
                            BrowserAdBlocker.injectEarlyScripts(context, view, view?.url)
                        }
                    }

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view != null && callback != null) {
                            onShowCustomView(view, callback)
                        }
                    }

                    override fun onHideCustomView() {
                        onHideCustomView()
                    }
                }

                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                    try {
                        val fileName = extractFileName(downloadUrl, contentDisposition, mimetype)
                        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                            setTitle(fileName)
                            setDescription("Đang tải tệp $fileName...")
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                            addRequestHeader("User-Agent", userAgent)
                            CookieManager.getInstance().getCookie(downloadUrl)?.let { cookie ->
                                if (cookie.isNotBlank()) addRequestHeader("Cookie", cookie)
                            }
                        }
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                        dm?.enqueue(request)
                        Toast.makeText(context, "Bắt đầu tải: $fileName", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Timber.e(e, "DownloadManager failed for url: %s", downloadUrl)
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                        }
                    }
                }

                val startUrl = if (initialUrl.isNotBlank()) initialUrl else uiState.currentUrl
                loadUrl(startUrl)
                onWebViewReady(this)
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

private val BLOCKED_POPUP_HOST_KEYWORDS = listOf(
    "popads", "popcash", "propeller", "adsterra", "clickadu", "exoclick",
    "fantastindents", "excidekombu", "cleverwebserver", "adsboosters",
    "92mim", "tzegilo", "vr-gc", "dd133", "becorsolaom", "apps2app",
    "vignette", "adxcontent", "vlit", "doubleclick", "adnxs",
    "taboola", "mgid", "affiliate", "shopee", "lazada", "offerflowtogo"
)

private fun isBlockedPopupUrl(url: String): Boolean {
    val lower = url.lowercase()
    return BLOCKED_POPUP_HOST_KEYWORDS.any { lower.contains(it) }
}
