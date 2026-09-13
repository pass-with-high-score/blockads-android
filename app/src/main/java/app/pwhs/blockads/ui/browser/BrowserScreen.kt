package app.pwhs.blockads.ui.browser

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
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import timber.log.Timber
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.ui.browser.component.BrowserBottomBar
import app.pwhs.blockads.ui.browser.component.BrowserShieldSheet
import app.pwhs.blockads.ui.browser.component.BrowserShortcuts
import app.pwhs.blockads.ui.browser.component.BrowserTopBar
import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String = "https://m.youtube.com",
    isInPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    onCloseBrowser: () -> Unit,
    viewModel: BrowserViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val shieldSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showShieldSheet by remember { mutableStateOf(false) }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && initialUrl != uiState.currentUrl) {
            viewModel.processIntent(BrowserUiIntent.LoadUrl(initialUrl))
            webViewInstance?.loadUrl(initialUrl)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collectLatest { effect ->
            when (effect) {
                is BrowserUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is BrowserUiEffect.OpenExternal -> {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                        context.startActivity(intent)
                    }.onFailure {
                        Toast.makeText(context, "Không thể mở ứng dụng ngoài", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    BackHandler(enabled = !isInPipMode) {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        } else if (uiState.showShortcuts) {
            viewModel.processIntent(BrowserUiIntent.ToggleShortcuts)
        } else if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onCloseBrowser()
        }
    }

    LaunchedEffect(isInPipMode) {
        val js = if (isInPipMode) {
            """
            (function() {
                if (window.__blockads_set_pip) {
                    window.__blockads_set_pip(true);
                } else {
                    var v = document.querySelector('video');
                    if (v) {
                        var box = document.getElementById('__blockads_pip_box');
                        if (!box) {
                            box = document.createElement('div');
                            box.id = '__blockads_pip_box';
                            box.style.cssText = 'position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;display:flex!important;align-items:center!important;justify-content:center!important;';
                            document.body.appendChild(box);
                        }
                        if (!v._blockadsOrigParent) {
                            v._blockadsOrigParent = v.parentNode;
                            v._blockadsOrigSibling = v.nextSibling;
                        }
                        box.appendChild(v);
                        v.style.cssText = 'width:100%!important;height:100%!important;object-fit:contain!important;background:#000!important;display:block!important;';
                        if (v.paused) v.play().catch(function(){});
                    }
                }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                if (window.__blockads_set_pip) {
                    window.__blockads_set_pip(false);
                } else {
                    var v = document.querySelector('video');
                    var box = document.getElementById('__blockads_pip_box');
                    if (v && v._blockadsOrigParent) {
                        v.style.cssText = '';
                        try { v._blockadsOrigParent.insertBefore(v, v._blockadsOrigSibling); }
                        catch(e) { v._blockadsOrigParent.appendChild(v); }
                        delete v._blockadsOrigParent;
                        delete v._blockadsOrigSibling;
                    }
                    if (box) box.remove();
                }
            })();
            """.trimIndent()
        }
        webViewInstance?.evaluateJavascript(js, null)
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (customView == null && !isInPipMode) {
                BrowserTopBar(
                    displayUrl = uiState.displayUrl,
                    progress = uiState.progress,
                    isLoading = uiState.isLoading,
                    blockedCount = uiState.blockedCount,
                    isDesktopMode = uiState.isDesktopMode,
                    onUrlSubmit = { url ->
                        viewModel.processIntent(BrowserUiIntent.LoadUrl(url))
                        webViewInstance?.loadUrl(BrowserAdBlocker.sanitizeSearchUrl(url))
                    },
                    onReload = {
                        webViewInstance?.reload()
                    },
                    onToggleDesktopMode = {
                        viewModel.processIntent(BrowserUiIntent.ToggleDesktopMode)
                        webViewInstance?.settings?.let { settings ->
                            settings.userAgentString = if (!uiState.isDesktopMode) DESKTOP_USER_AGENT else null
                            webViewInstance?.reload()
                        }
                    },
                    onClearData = {
                        viewModel.processIntent(BrowserUiIntent.ClearData)
                        webViewInstance?.clearCache(true)
                    },
                    onOpenExternal = {
                        viewModel.processIntent(BrowserUiIntent.LoadUrl(uiState.displayUrl))
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.displayUrl))
                        context.startActivity(intent)
                    },
                    onEnterPip = {
                        webViewInstance?.evaluateJavascript(
                            "if (window.__blockads_set_pip) { window.__blockads_set_pip(true); }",
                            null
                        )
                        onEnterPip()
                    },
                    onOpenShieldSheet = { showShieldSheet = true },
                    onCloseBrowser = onCloseBrowser
                )
            }
        },
        bottomBar = {
            if (customView == null && !isInPipMode) {
                BrowserBottomBar(
                    canGoBack = webViewInstance?.canGoBack() == true,
                    canGoForward = webViewInstance?.canGoForward() == true,
                    adBlockEnabled = uiState.adBlockEnabled,
                    onBack = { webViewInstance?.goBack() },
                    onForward = { webViewInstance?.goForward() },
                    onHome = { viewModel.processIntent(BrowserUiIntent.ToggleShortcuts) },
                    onToggleAdBlock = {
                        viewModel.processIntent(BrowserUiIntent.ToggleAdBlock)
                        webViewInstance?.reload()
                    },
                    onShare = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, uiState.displayUrl)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                    onOpenShieldSheet = { showShieldSheet = true }
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (customView == null && !isInPipMode) padding else PaddingValues())
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
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
                            setSupportMultipleWindows(false)
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                            // AdGuard Chrome UA Spoofing
                            val defaultUa = userAgentString
                            userAgentString = BrowserAdBlocker.spoofChromeUserAgent(defaultUa)

                            // Algorithmic Darkening for Android 13+
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                isAlgorithmicDarkeningAllowed = true
                            }
                        }

                        val webView = this
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(webView, true)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (request != null && uiState.adBlockEnabled) {
                                    val fullUrl = request.url?.toString()?.lowercase(java.util.Locale.US) ?: ""
                                    val surrogate = BrowserAdBlocker.getSurrogateResponse(fullUrl)
                                    if (surrogate != null) {
                                        viewModel.processIntent(BrowserUiIntent.AdBlocked)
                                        return surrogate
                                    }
                                    if (BrowserAdBlocker.shouldBlock(request)) {
                                        viewModel.processIntent(BrowserUiIntent.AdBlocked)
                                        return BrowserAdBlocker.createBlockedResponse()
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url ?: return false
                                val scheme = url.scheme?.lowercase(java.util.Locale.US) ?: return false

                                if (scheme != "http" && scheme != "https") {
                                    // Block unwanted app scheme hijacking from ad scripts
                                    val blockedSchemes = listOf("snssdk", "tiktok", "musically", "shopee", "lazada")
                                    if (blockedSchemes.any { scheme.startsWith(it) }) {
                                        return true
                                    }
                                    // Block malicious non-user gesture redirects (e.g. ad apps/stores)
                                    if (request.hasGesture().not()) {
                                        return true
                                    }
                                    return runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, url)
                                        context.startActivity(intent)
                                        true
                                    }.getOrDefault(true)
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { viewModel.processIntent(BrowserUiIntent.PageStarted(it)) }

                                if (uiState.adBlockEnabled) {
                                    BrowserAdBlocker.injectEarlyScripts(context, view, url)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val currentTitle = view?.title ?: ""
                                url?.let { viewModel.processIntent(BrowserUiIntent.PageFinished(it, currentTitle)) }

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
                                // Block all window creation requests from ads/scripts
                                return false
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                viewModel.processIntent(BrowserUiIntent.UpdateProgress(newProgress))
                                if (newProgress in 15..25 && uiState.adBlockEnabled) {
                                    BrowserAdBlocker.injectEarlyScripts(context, view, view?.url)
                                }
                            }

                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                customView = view
                                customViewCallback = callback
                            }

                            override fun onHideCustomView() {
                                customView = null
                                customViewCallback?.onCustomViewHidden()
                                customViewCallback = null
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
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                    context.startActivity(intent)
                                }
                            }
                        }

                        val startUrl = if (initialUrl.isNotBlank()) initialUrl else uiState.currentUrl
                        loadUrl(startUrl)
                        webViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Fullscreen video overlay
            customView?.let { fullView ->
                AndroidView(
                    factory = { fullView },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            // Shortcuts Overlay
            AnimatedVisibility(visible = uiState.showShortcuts && customView == null && !isInPipMode) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize()
                ) {
                    BrowserShortcuts(
                        onSelectShortcut = { url ->
                            viewModel.processIntent(BrowserUiIntent.LoadUrl(url))
                            webViewInstance?.loadUrl(url)
                        }
                    )
                }
            }
        }
    }

    if (showShieldSheet) {
        BrowserShieldSheet(
            sheetState = shieldSheetState,
            currentUrl = uiState.currentUrl,
            blockedCount = uiState.blockedCount,
            adBlockEnabled = uiState.adBlockEnabled,
            isDesktopMode = uiState.isDesktopMode,
            ruleVersion = uiState.ruleVersion,
            ruleDomainsCount = uiState.ruleDomainsCount,
            isCheckingRuleUpdates = uiState.isCheckingRuleUpdates,
            onDismiss = { showShieldSheet = false },
            onToggleAdBlock = {
                viewModel.processIntent(BrowserUiIntent.ToggleAdBlock)
                webViewInstance?.reload()
            },
            onToggleDesktopMode = {
                viewModel.processIntent(BrowserUiIntent.ToggleDesktopMode)
                webViewInstance?.settings?.let { settings ->
                    settings.userAgentString = if (!uiState.isDesktopMode) DESKTOP_USER_AGENT else null
                    webViewInstance?.reload()
                }
            },
            onEnterPip = {
                webViewInstance?.evaluateJavascript(
                    "if (window.__blockads_set_pip) { window.__blockads_set_pip(true); }",
                    null
                )
                onEnterPip()
            },
            onClearData = {
                viewModel.processIntent(BrowserUiIntent.ClearData)
                webViewInstance?.clearCache(true)
            },
            onOpenExternal = {
                viewModel.processIntent(BrowserUiIntent.LoadUrl(uiState.displayUrl))
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uiState.displayUrl))
                context.startActivity(intent)
            },
            onCheckRuleUpdates = {
                viewModel.processIntent(BrowserUiIntent.CheckRuleUpdates)
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            customView = null
            customViewCallback = null
            webViewInstance?.destroy()
        }
    }
}
