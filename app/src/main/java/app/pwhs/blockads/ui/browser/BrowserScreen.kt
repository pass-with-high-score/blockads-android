package app.pwhs.blockads.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.ui.browser.component.BrowserBottomBar
import app.pwhs.blockads.ui.browser.component.BrowserShortcuts
import app.pwhs.blockads.ui.browser.component.BrowserTopBar
import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String = "https://m.youtube.com",
    onCloseBrowser: () -> Unit,
    viewModel: BrowserViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        if (initialUrl.isNotEmpty() && initialUrl != uiState.currentUrl) {
            viewModel.processIntent(BrowserUiIntent.LoadUrl(initialUrl))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collectLatest { effect ->
            when (effect) {
                is BrowserUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is BrowserUiEffect.OpenExternal -> {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "Không thể mở ứng dụng ngoài", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    BackHandler {
        if (uiState.showShortcuts) {
            viewModel.processIntent(BrowserUiIntent.ToggleShortcuts)
        } else if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onCloseBrowser()
        }
    }

    Scaffold(
        topBar = {
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
                onCloseBrowser = onCloseBrowser
            )
        },
        bottomBar = {
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
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (request != null && uiState.adBlockEnabled) {
                                    if (BrowserAdBlocker.shouldBlock(request)) {
                                        viewModel.processIntent(BrowserUiIntent.AdBlocked)
                                        return BrowserAdBlocker.createBlockedResponse()
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { viewModel.processIntent(BrowserUiIntent.PageStarted(it)) }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val currentTitle = view?.title ?: ""
                                url?.let { viewModel.processIntent(BrowserUiIntent.PageFinished(it, currentTitle)) }

                                if (uiState.adBlockEnabled) {
                                    view?.evaluateJavascript(BrowserAdBlocker.COSMETIC_CSS_SCRIPT, null)
                                    if (url?.contains("youtube.com") == true) {
                                        view?.evaluateJavascript(BrowserAdBlocker.YOUTUBE_AD_SKIP_SCRIPT, null)
                                    }
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                viewModel.processIntent(BrowserUiIntent.UpdateProgress(newProgress))
                            }
                        }

                        loadUrl(uiState.currentUrl)
                        webViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Shortcuts Overlay
            AnimatedVisibility(visible = uiState.showShortcuts) {
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

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
        }
    }
}
