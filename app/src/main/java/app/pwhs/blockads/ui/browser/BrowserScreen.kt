package app.pwhs.blockads.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.util.Rational
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.browser.component.BrowserBentoMenuSheet
import app.pwhs.blockads.ui.browser.component.BrowserBottomOmnibox
import app.pwhs.blockads.ui.browser.component.BrowserShortcuts
import app.pwhs.blockads.ui.browser.component.BrowserWebView
import app.pwhs.blockads.ui.browser.component.SearchSuggestionSheet
import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String = "https://m.youtube.com",
    isInPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    onVideoPlaybackChanged: (Boolean) -> Unit = {},
    onVideoBoundsChanged: (Rect?, Rational, Boolean) -> Unit = { _, _, _ -> },
    isAutoPipEnabled: Boolean = true,
    onToggleAutoPip: () -> Unit = {},
    onCloseBrowser: () -> Unit,
    onNavigateToElementRules: () -> Unit = {},
    viewModel: BrowserViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    var isVideoPlaying by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                webViewInstance?.onResume()
                webViewInstance?.evaluateJavascript(
                    """
                    (function() {
                        var vids = document.querySelectorAll('video');
                        for (var i = 0; i < vids.length; i++) {
                            var v = vids[i];
                            if (!v.paused) {
                                v.style.top = '0px';
                                v.dispatchEvent(new Event('canplay'));
                                v.dispatchEvent(new Event('playing'));
                                v.dispatchEvent(new Event('timeupdate'));
                                try { v.play().catch(function(){}); } catch(e) {}
                            }
                        }
                        var mp = document.querySelector('#movie_player');
                        if (mp) {
                            if (typeof mp.updateLastActiveTime === 'function') {
                                try { mp.updateLastActiveTime(); } catch(e) {}
                            }
                            var anyPlaying = false;
                            for (var j = 0; j < vids.length; j++) {
                                if (!vids[j].paused) { anyPlaying = true; break; }
                            }
                            if (anyPlaying) {
                                mp.classList.remove('paused-mode');
                                mp.classList.add('playing-mode');
                                mp.classList.add('ytp-autohide-active');
                                if (typeof mp.playVideo === 'function') {
                                    try { mp.playVideo(); } catch(e) {}
                                }
                                var overlay = document.querySelector('#player-control-overlay');
                                if (overlay) {
                                    overlay.classList.remove('fadein');
                                    overlay.classList.add('fadeout');
                                }
                            }
                        }
                        var dialogs = document.querySelectorAll('dialog, ytm-dialog-renderer, ytm-you-there-renderer, #dialog-container dialog');
                        for (var k = 0; k < dialogs.length; k++) {
                            var d = dialogs[k];
                            var txt = (d.textContent || '').toLowerCase();
                            if (txt.includes('video paused') || txt.includes('continue watching') ||
                                txt.includes('tạm dừng') || txt.includes('tiếp tục xem') ||
                                d.querySelector('.confirm-dialog-content')) {
                                var btn = d.querySelector('button');
                                if (btn) btn.click();
                                if (typeof d.close === 'function') {
                                    try { d.close(); } catch(e) {}
                                }
                            }
                        }
                        window.dispatchEvent(new Event('resize'));
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(webViewInstance) {
        app.pwhs.blockads.ui.browser.media.BrowserMediaBridge.attach(webViewInstance)
    }

    DisposableEffect(Unit) {
        onDispose {
            app.pwhs.blockads.ui.browser.media.BrowserMediaBridge.detach()
            app.pwhs.blockads.ui.browser.media.BrowserMediaCoordinator.stopMedia(context)
        }
    }

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    LaunchedEffect(uiState.showShortcuts) {
        if (uiState.showShortcuts) {
            isVideoPlaying = false
            onVideoPlaybackChanged(false)
            onVideoBoundsChanged(null, Rational(16, 9), false)
            app.pwhs.blockads.ui.browser.media.BrowserMediaCoordinator.stopMedia(context)
        }
    }

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
                        Toast.makeText(context, R.string.browser_open_external_error, Toast.LENGTH_SHORT).show()
                    }
                }
                is BrowserUiEffect.NavigateUrl -> {
                    webViewInstance?.loadUrl(effect.url)
                }
                is BrowserUiEffect.InjectUserElementRules -> {
                    BrowserAdBlocker.injectUserElementRules(webViewInstance, effect.selectors)
                }
                is BrowserUiEffect.NavigateToElementRules -> {
                    onNavigateToElementRules()
                }
            }
        }
    }

    LaunchedEffect(uiState.isElementPickerActive) {
        if (uiState.isElementPickerActive) {
            val js = runCatching {
                context.assets.open("element_picker.js").bufferedReader().use { it.readText() }
            }.getOrDefault("")
            if (js.isNotBlank()) {
                webViewInstance?.evaluateJavascript(js, null)
            }
        } else {
            webViewInstance?.evaluateJavascript(
                "if (window.__blockadsPickerCancel__) { window.__blockadsPickerCancel__(); }",
                null
            )
        }
    }

    BackHandler(enabled = !isInPipMode) {
        if (uiState.isElementPickerActive) {
            viewModel.processIntent(BrowserUiIntent.DeactivateElementPicker)
        } else if (customView != null) {
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
            if (customView == null && !isInPipMode && !uiState.isElementPickerActive) {
                BrowserBottomOmnibox(
                    displayUrl = uiState.displayUrl,
                    progress = uiState.progress,
                    isLoading = uiState.isLoading,
                    blockedCount = uiState.blockedCount,
                    adBlockEnabled = uiState.adBlockEnabled,
                    canGoBack = webViewInstance?.canGoBack() == true,
                    canGoForward = webViewInstance?.canGoForward() == true,
                    isDesktopMode = uiState.isDesktopMode,
                    isVideoPlaying = isVideoPlaying,
                    isVisible = uiState.isBottomBarVisible,
                    onBack = { webViewInstance?.goBack() },
                    onForward = { webViewInstance?.goForward() },
                    onReload = { webViewInstance?.reload() },
                    onStop = { webViewInstance?.stopLoading() },
                    onOpenSearch = { viewModel.processIntent(BrowserUiIntent.ToggleSearchSheet(true)) },
                    onOpenMenu = { viewModel.processIntent(BrowserUiIntent.ToggleBentoMenu(true)) },
                    onHome = { viewModel.processIntent(BrowserUiIntent.ToggleShortcuts) },
                    onEnterPip = {
                        webViewInstance?.evaluateJavascript(
                            "if (window.__blockads_set_pip) { window.__blockads_set_pip(true); }",
                            null
                        )
                        onEnterPip()
                    }
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
                .padding(if (customView == null && !isInPipMode && !uiState.isElementPickerActive) padding else PaddingValues())
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    webViewInstance?.reload()
                },
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullToRefreshState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.fillMaxSize()
            ) {
                BrowserWebView(
                    uiState = uiState,
                    initialUrl = initialUrl,
                    onIntent = viewModel::processIntent,
                    onWebViewReady = { webViewInstance = it },
                    onPullRefresh = {
                        isRefreshing = true
                        webViewInstance?.reload()
                    },
                    onShowCustomView = { view, callback ->
                        customView = view
                        customViewCallback = callback
                        isVideoPlaying = true
                        onVideoPlaybackChanged(true)
                        onVideoBoundsChanged(null, Rational(16, 9), true)
                    },
                    onHideCustomView = {
                        customView = null
                        customViewCallback?.onCustomViewHidden()
                        customViewCallback = null
                        isVideoPlaying = false
                        onVideoPlaybackChanged(false)
                        onVideoBoundsChanged(null, Rational(16, 9), false)
                    },
                    onVideoPlaybackChanged = { playing ->
                        isVideoPlaying = playing
                        onVideoPlaybackChanged(playing)
                    },
                    onVideoBoundsChanged = onVideoBoundsChanged,
                    modifier = Modifier.fillMaxSize()
                )
            }


            // Fullscreen video overlay
            customView?.let { fullView ->
                AndroidView(
                    factory = { fullView },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            // Shortcuts Overlay - animate smoothly from center outwards
            AnimatedVisibility(
                visible = uiState.showShortcuts && customView == null && !isInPipMode,
                enter = fadeIn(animationSpec = tween(220)) + scaleIn(
                    initialScale = 0.90f,
                    transformOrigin = TransformOrigin.Center,
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(animationSpec = tween(180)) + scaleOut(
                    targetScale = 0.90f,
                    transformOrigin = TransformOrigin.Center,
                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                )
            ) {
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
        popupBlockEnabled = uiState.popupBlockEnabled,
        isDesktopMode = uiState.isDesktopMode,
        isAutoPipEnabled = isAutoPipEnabled,
        ruleVersion = uiState.ruleVersion,
        ruleDomainsCount = uiState.ruleDomainsCount,
        isCheckingRuleUpdates = uiState.isCheckingRuleUpdates,
        onDismiss = { viewModel.processIntent(BrowserUiIntent.ToggleBentoMenu(false)) },
        onToggleAdBlock = {
            viewModel.processIntent(BrowserUiIntent.ToggleAdBlock)
            webViewInstance?.reload()
        },
        onTogglePopupBlock = {
            viewModel.processIntent(BrowserUiIntent.TogglePopupBlock)
        },
        onToggleDesktopMode = {
            viewModel.processIntent(BrowserUiIntent.ToggleDesktopMode)
            webViewInstance?.settings?.let { settings ->
                settings.userAgentString = if (!uiState.isDesktopMode) DESKTOP_USER_AGENT else null
                webViewInstance?.reload()
            }
        },
        onToggleAutoPip = onToggleAutoPip,
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
        onCloseBrowser = onCloseBrowser,
        onCheckRuleUpdates = {
            viewModel.processIntent(BrowserUiIntent.CheckRuleUpdates)
        },
        onActivateElementPicker = {
            viewModel.processIntent(BrowserUiIntent.ActivateElementPicker)
        },
        onNavigateToElementRules = {
            viewModel.processIntent(BrowserUiIntent.NavigateToElementRules)
        },
        onOpenDebugLogger = {
            viewModel.processIntent(BrowserUiIntent.ToggleDebugLogger(true))
        }
    )

    app.pwhs.blockads.ui.logs.component.HttpDebugLoggerSheet(
        isVisible = uiState.isDebugLoggerVisible,
        onDismiss = { viewModel.processIntent(BrowserUiIntent.ToggleDebugLogger(false)) }
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
