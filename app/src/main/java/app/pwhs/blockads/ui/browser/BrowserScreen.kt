package app.pwhs.blockads.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
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
                        Toast.makeText(context, "Không thể mở ứng dụng ngoài", Toast.LENGTH_SHORT).show()
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
                    },
                    onHideCustomView = {
                        customView = null
                        customViewCallback?.onCustomViewHidden()
                        customViewCallback = null
                    },
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
        popupBlockEnabled = uiState.popupBlockEnabled,
        isDesktopMode = uiState.isDesktopMode,
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
        },
        onActivateElementPicker = {
            viewModel.processIntent(BrowserUiIntent.ActivateElementPicker)
        },
        onNavigateToElementRules = {
            viewModel.processIntent(BrowserUiIntent.NavigateToElementRules)
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
