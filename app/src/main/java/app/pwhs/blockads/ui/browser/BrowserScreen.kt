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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.pwhs.blockads.ui.browser.component.BrowserBottomOmnibox
import app.pwhs.blockads.ui.browser.component.BrowserBentoMenuSheet
import app.pwhs.blockads.ui.browser.component.BrowserShortcuts
import app.pwhs.blockads.ui.browser.component.SearchSuggestionSheet
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
                is BrowserUiEffect.NavigateUrl -> {
                    webViewInstance?.loadUrl(effect.url)
                }
            }
        }
    }

    BackHandler(enabled = !isInPipMode) {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        } else if (uiState.isSearchSheetVisible) {
            viewModel.processIntent(BrowserUiIntent.ToggleSearchSheet(false))
        } else if (uiState.isBentoMenuVisible) {
            viewModel.processIntent(BrowserUiIntent.ToggleBentoMenu(false))
        } else if (uiState.showShortcuts) {
            viewModel.processIntent(BrowserUiIntent.ToggleShortcuts)
        } else if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onCloseBrowser()
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < -12f && uiState.isBottomBarVisible) {
                    viewModel.processIntent(BrowserUiIntent.UpdateBottomBarVisibility(false))
                } else if (delta > 12f && !uiState.isBottomBarVisible) {
                    viewModel.processIntent(BrowserUiIntent.UpdateBottomBarVisibility(true))
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(isInPipMode) {
        val js = app.pwhs.blockads.ui.browser.util.BrowserPipHelper.getPipToggleScript(isInPipMode)
        webViewInstance?.evaluateJavascript(js, null)
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            if (customView == null && !isInPipMode) {
                BrowserBottomOmnibox(
                    displayUrl = uiState.displayUrl,
                    progress = uiState.progress,
                    isLoading = uiState.isLoading,
                    blockedCount = uiState.blockedCount,
                    adBlockEnabled = uiState.adBlockEnabled,
                    canGoBack = webViewInstance?.canGoBack() == true,
                    canGoForward = webViewInstance?.canGoForward() == true,
                    isDesktopMode = uiState.isDesktopMode,
                    isVisible = uiState.isBottomBarVisible,
                    onBack = { webViewInstance?.goBack() },
                    onForward = { webViewInstance?.goForward() },
                    onReload = { webViewInstance?.reload() },
                    onOpenSearch = { viewModel.processIntent(BrowserUiIntent.ToggleSearchSheet(true)) },
                    onOpenMenu = { viewModel.processIntent(BrowserUiIntent.ToggleBentoMenu(true)) }
                )
            }
        },
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
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
                            setSupportMultipleWindows(true)
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

                                if (uiState.adBlockEnabled && BrowserAdBlocker.shouldBlockNavigation(request, view?.url)) {
                                    viewModel.processIntent(BrowserUiIntent.AdBlocked)
                                    return true
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
                                if (!isUserGesture) return false
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                val tempWebView = WebView(view?.context ?: return false)
                                tempWebView.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val targetUrl = request?.url?.toString() ?: return false
                                        if (uiState.adBlockEnabled && BrowserAdBlocker.shouldBlock(request)) {
                                            viewModel.processIntent(BrowserUiIntent.AdBlocked)
                                            return true
                                        }
                                        viewModel.processIntent(BrowserUiIntent.LoadUrl(targetUrl))
                                        return true
                                    }
                                }
                                transport.webView = tempWebView
                                resultMsg.sendToTarget()
                                return true
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
                    color = Color.Black,
                    modifier = Modifier.fillMaxSize()
                ) {
                    BrowserShortcuts(
                        onSelectShortcut = { url ->
                            viewModel.processIntent(BrowserUiIntent.LoadUrl(url))
                        },
                        onOpenSearch = { viewModel.processIntent(BrowserUiIntent.ToggleSearchSheet(true)) },
                        onOpenMenu = { viewModel.processIntent(BrowserUiIntent.ToggleBentoMenu(true)) }
                    )
                }
            }
        }
    }

    BrowserBentoMenuSheet(
        isVisible = uiState.isBentoMenuVisible,
        blockedCount = uiState.blockedCount,
        adBlockEnabled = uiState.adBlockEnabled,
        isDesktopMode = uiState.isDesktopMode,
        ruleVersion = uiState.ruleVersion,
        ruleDomainsCount = uiState.ruleDomainsCount,
        isCheckingRuleUpdates = uiState.isCheckingRuleUpdates,
        onDismiss = { viewModel.processIntent(BrowserUiIntent.ToggleBentoMenu(false)) },
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
        onShare = {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, uiState.displayUrl)
                type = "text/plain"
            }
            context.startActivity(Intent.createChooser(sendIntent, null))
        },
        onHome = { viewModel.processIntent(BrowserUiIntent.ToggleShortcuts) },
        onCloseBrowser = onCloseBrowser,
        onCheckRuleUpdates = {
            viewModel.processIntent(BrowserUiIntent.CheckRuleUpdates)
        }
    )

    SearchSuggestionSheet(
        query = uiState.searchQuery,
        suggestions = uiState.suggestions,
        selectedEngine = uiState.selectedSearchEngine,
        isVisible = uiState.isSearchSheetVisible,
        onQueryChange = { viewModel.processIntent(BrowserUiIntent.UpdateSearchQuery(it)) },
        onEngineSelect = { viewModel.processIntent(BrowserUiIntent.SelectSearchEngine(it)) },
        onSubmitSearch = { urlOrQuery ->
            viewModel.processIntent(BrowserUiIntent.SubmitSearch(urlOrQuery))
        },
        onDismiss = { viewModel.processIntent(BrowserUiIntent.ToggleSearchSheet(false)) }
    )

    DisposableEffect(Unit) {
        onDispose {
            customView = null
            customViewCallback = null
            webViewInstance?.destroy()
        }
    }
}
