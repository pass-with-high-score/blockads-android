package app.pwhs.blockads.ui.browser.component

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Rational
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
import androidx.core.net.toUri
import app.pwhs.blockads.R
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
    onVideoPlaybackChanged: (Boolean) -> Unit = {},
    onVideoBoundsChanged: (Rect?, Rational, Boolean) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    AndroidView(
        factory = { ctx ->
            PullRefreshWebView(ctx).apply {
                onPullToRefreshTrigger = onPullRefresh
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
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
                    offscreenPreRaster = true

                    val defaultUa = userAgentString
                    userAgentString = BrowserAdBlocker.spoofChromeUserAgent(defaultUa)
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

                // Register video playback monitor for smart PiP auto-enter and source rect hint
                addJavascriptInterface(
                    object {
                        @android.webkit.JavascriptInterface
                        fun onPlaybackChanged(isPlaying: Boolean) {
                            webView.post { onVideoPlaybackChanged(isPlaying) }
                        }

                        @android.webkit.JavascriptInterface
                        fun updateVideoBounds(
                            isPlaying: Boolean,
                            left: Float,
                            top: Float,
                            width: Float,
                            height: Float,
                            aspectNum: Int,
                            aspectDen: Int
                        ) {
                            webView.post {
                                onVideoPlaybackChanged(isPlaying)
                                val location = IntArray(2)
                                webView.getLocationOnScreen(location)
                                val density = webView.resources.displayMetrics.density
                                val screenWidth = webView.resources.displayMetrics.widthPixels
                                val screenHeight = webView.resources.displayMetrics.heightPixels

                                val leftPx = (left * density).toInt() + location[0]
                                val topPx = (top * density).toInt() + location[1]
                                val rightPx = ((left + width) * density).toInt() + location[0]
                                val bottomPx = ((top + height) * density).toInt() + location[1]

                                val safeRect = if (width > 20f && height > 20f && bottomPx > 0 && topPx < screenHeight) {
                                    Rect(
                                        leftPx.coerceIn(0, screenWidth),
                                        topPx.coerceIn(0, screenHeight),
                                        rightPx.coerceIn(0, screenWidth),
                                        bottomPx.coerceIn(0, screenHeight)
                                    )
                                } else null

                                val ratio = if (aspectDen > 0) aspectNum.toFloat() / aspectDen.toFloat() else 1.7778f
                                val safeRatio = when {
                                    ratio < 0.41841f -> Rational(100, 239)
                                    ratio > 2.39f -> Rational(239, 100)
                                    aspectNum > 0 && aspectDen > 0 -> Rational(aspectNum, aspectDen)
                                    else -> Rational(16, 9)
                                }

                                onVideoBoundsChanged(safeRect, safeRatio, isPlaying)
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun updateMediaState(
                            title: String,
                            artist: String,
                            artworkUrl: String,
                            isPlaying: Boolean,
                            currentSec: Double,
                            durationSec: Double
                        ) {
                            webView.post {
                                val host = runCatching { Uri.parse(webView.url ?: uiState.displayUrl).host }.getOrNull().orEmpty()
                                app.pwhs.blockads.ui.browser.media.BrowserMediaCoordinator.updateMediaState(
                                    context = context,
                                    title = title.ifBlank { uiState.displayUrl },
                                    artist = artist.ifBlank { host },
                                    artworkUrl = artworkUrl,
                                    playing = isPlaying,
                                    posMs = (currentSec * 1000).toLong(),
                                    durMs = (durationSec * 1000).toLong()
                                )
                            }
                        }
                    },
                    "__blockads_video_bridge"
                )
                addJavascriptInterface(app.pwhs.blockads.ui.browser.interceptor.BrowserRequestLogger.JsLoggerBridge(), "__blockadsLogger")

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        return app.pwhs.blockads.ui.browser.interceptor.BrowserRequestLogger.interceptAndLog(
                            request = request,
                            adBlockEnabled = uiState.adBlockEnabled,
                            onAdBlocked = { onIntent(BrowserUiIntent.AdBlocked) }
                        ) ?: super.shouldInterceptRequest(view, request)
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
                        onVideoPlaybackChanged(false)
                        onVideoBoundsChanged(null, Rational(16, 9), false)
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
                        view?.evaluateJavascript(
                            """
                            (function() {
                                if (window.__blockads_video_bridge_installed) return;
                                window.__blockads_video_bridge_installed = true;
                                function reportVideo() {
                                    if (!window.__blockads_video_bridge) return;
                                    var vids = document.querySelectorAll('video');
                                    var activeVid = null;
                                    for (var i = 0; i < vids.length; i++) {
                                        var v = vids[i];
                                        if (!v.paused && !v.ended && v.currentTime > 0) {
                                            activeVid = v;
                                            break;
                                        }
                                    }
                                    var ytPlayer = document.querySelector('#movie_player') || document.querySelector('#player');
                                    var isYtPlaying = false;
                                    if (ytPlayer && typeof ytPlayer.getPlayerState === 'function') {
                                        isYtPlaying = (ytPlayer.getPlayerState() === 1);
                                    }
                                    var isPlaying = !!activeVid || isYtPlaying;
                                    window.__blockads_is_video_playing = isPlaying;
                                    var target = activeVid || ytPlayer || (vids.length > 0 ? vids[0] : null);
                                    if (!target || !isPlaying) {
                                        if (window.__blockads_video_bridge) {
                                            window.__blockads_video_bridge.onPlaybackChanged(isPlaying);
                                        }
                                        return;
                                    }
                                    var r = target.getBoundingClientRect();
                                    var vw = (activeVid && activeVid.videoWidth > 0) ? activeVid.videoWidth : r.width;
                                    var vh = (activeVid && activeVid.videoHeight > 0) ? activeVid.videoHeight : r.height;
                                    if (ytPlayer && typeof ytPlayer.getVideoAspectRatio === 'function') {
                                        var ar = ytPlayer.getVideoAspectRatio();
                                        if (ar > 0) {
                                            vw = Math.round(1000 * ar);
                                            vh = 1000;
                                        }
                                    }
                                    if (window.__blockads_video_bridge.updateVideoBounds) {
                                        window.__blockads_video_bridge.updateVideoBounds(
                                            isPlaying,
                                            r.left,
                                            r.top,
                                            r.width,
                                            r.height,
                                            Math.round(vw),
                                            Math.round(vh)
                                        );
                                    } else {
                                        window.__blockads_video_bridge.onPlaybackChanged(isPlaying);
                                    }

                                    try {
                                        var mediaTitle = '';
                                        var mediaArtist = '';
                                        var mediaArt = '';
                                        if (navigator.mediaSession && navigator.mediaSession.metadata) {
                                            mediaTitle = navigator.mediaSession.metadata.title || '';
                                            mediaArtist = navigator.mediaSession.metadata.artist || '';
                                            var arts = navigator.mediaSession.metadata.artwork;
                                            if (arts && arts.length > 0) {
                                                mediaArt = arts[arts.length - 1].src || '';
                                            }
                                        }
                                        if (!mediaTitle) {
                                            mediaTitle = (document.title || '').replace(/\s*-\s*YouTube$/i, '').trim();
                                        }
                                        if (!mediaTitle) {
                                            var h1 = document.querySelector('.slim-video-metadata-title, h1.title, .video-details h1');
                                            if (h1) mediaTitle = (h1.textContent || '').trim();
                                        }
                                        if (!mediaArtist) {
                                            var ch = document.querySelector('.ytm-slim-video-metadata-renderer-byline, ytm-badge-and-byline-renderer, .byline, ytm-channel-name');
                                            mediaArtist = ch ? (ch.textContent || '').trim() : window.location.hostname;
                                        }
                                        if (!mediaArt) {
                                            var m = location.href.match(/(?:v=|shorts\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
                                            if (m && m[1]) {
                                                mediaArt = 'https://i.ytimg.com/vi/' + m[1] + '/hqdefault.jpg';
                                            } else {
                                                var og = document.querySelector('meta[property="og:image"], meta[name="twitter:image"], link[rel="image_src"]');
                                                if (og) mediaArt = og.content || og.href || '';
                                                else if (activeVid && activeVid.poster) mediaArt = activeVid.poster;
                                            }
                                        }
                                        var mCur = (activeVid && activeVid.currentTime) ? activeVid.currentTime : 0;
                                        var mDur = (activeVid && isFinite(activeVid.duration)) ? activeVid.duration : 0;
                                        if (ytPlayer && typeof ytPlayer.getCurrentTime === 'function') {
                                            if (!mCur) mCur = ytPlayer.getCurrentTime() || 0;
                                            if (!mDur && typeof ytPlayer.getDuration === 'function') mDur = ytPlayer.getDuration() || 0;
                                        }
                                        if (window.__blockads_video_bridge && window.__blockads_video_bridge.updateMediaState) {
                                            window.__blockads_video_bridge.updateMediaState(
                                                mediaTitle || 'YouTube',
                                                mediaArtist || 'BlockAds Browser',
                                                mediaArt,
                                                isPlaying,
                                                mCur,
                                                mDur
                                            );
                                        }
                                    } catch (e) {}
                                }
                                window.__blockads_report_video = reportVideo;
                                function scheduleReport(delay) {
                                    if (pauseReportTimer) {
                                        clearTimeout(pauseReportTimer);
                                        pauseReportTimer = null;
                                    }
                                    if (delay > 0) {
                                        pauseReportTimer = setTimeout(reportVideo, delay);
                                    } else {
                                        reportVideo();
                                    }
                                }
                                document.addEventListener('play', function() { scheduleReport(0); }, true);
                                document.addEventListener('playing', function() { scheduleReport(0); }, true);
                                document.addEventListener('pause', function() { scheduleReport(700); }, true);
                                document.addEventListener('ended', function() { scheduleReport(0); }, true);
                                document.addEventListener('timeupdate', function() {
                                    if (!window.__blockads_last_tu || Date.now() - window.__blockads_last_tu > 2500) {
                                        window.__blockads_last_tu = Date.now();
                                        reportVideo();
                                    }
                                }, true);
                                var scrollTimer = null;
                                window.addEventListener('scroll', function() {
                                    if (scrollTimer) clearTimeout(scrollTimer);
                                    scrollTimer = setTimeout(function() {
                                        scrollTimer = null;
                                        if (window.__blockads_is_video_playing) {
                                            reportVideo();
                                        }
                                    }, 1200);
                                }, { passive: true });
                                window.addEventListener('resize', function() { scheduleReport(300); }, { passive: true });
                                window.addEventListener('yt-navigate-finish', function() { scheduleReport(500); }, { passive: true });
                                window.addEventListener('popstate', function() { scheduleReport(500); }, { passive: true });
                                setTimeout(reportVideo, 1200);
                            })();
                            """.trimIndent(),
                            null
                        )
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
                        val request = DownloadManager.Request(downloadUrl.toUri()).apply {
                            setTitle(fileName)
                            setDescription(appContext.getString(R.string.browser_download_desc, fileName))
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                            addRequestHeader("User-Agent", userAgent)
                            CookieManager.getInstance().getCookie(downloadUrl)?.let { cookie ->
                                if (cookie.isNotBlank()) addRequestHeader("Cookie", cookie)
                            }
                        }
                        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                        dm?.enqueue(request)
                        Toast.makeText(appContext, appContext.getString(R.string.browser_download_started, fileName), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Timber.e(e, "DownloadManager failed for url: %s", downloadUrl)
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, downloadUrl.toUri()))
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
