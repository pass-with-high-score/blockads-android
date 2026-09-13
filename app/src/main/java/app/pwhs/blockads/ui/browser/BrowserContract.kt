package app.pwhs.blockads.ui.browser

import app.pwhs.blockads.ui.browser.data.SearchEngine

data class BrowserUiState(
    val currentUrl: String = "https://m.youtube.com",
    val displayUrl: String = "https://m.youtube.com",
    val pageTitle: String = "YouTube",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isDesktopMode: Boolean = false,
    val adBlockEnabled: Boolean = true,
    val blockedCount: Int = 0,
    val isIncognito: Boolean = true,
    val showShortcuts: Boolean = false,
    val ruleVersion: Long = 1L,
    val ruleDomainsCount: Int = 0,
    val isCheckingRuleUpdates: Boolean = false,
    val searchQuery: String = "",
    val suggestions: List<String> = emptyList(),
    val selectedSearchEngine: SearchEngine = SearchEngine.GOOGLE,
    val isSearchSheetVisible: Boolean = false,
    val isBottomBarVisible: Boolean = true,
    val isBentoMenuVisible: Boolean = false
)

sealed interface BrowserUiIntent {
    data class LoadUrl(val url: String) : BrowserUiIntent
    data object Reload : BrowserUiIntent
    data object GoBack : BrowserUiIntent
    data object GoForward : BrowserUiIntent
    data object ToggleDesktopMode : BrowserUiIntent
    data object ToggleAdBlock : BrowserUiIntent
    data object ToggleShortcuts : BrowserUiIntent
    data object ClearData : BrowserUiIntent
    data class UpdateProgress(val progress: Int) : BrowserUiIntent
    data class PageStarted(val url: String) : BrowserUiIntent
    data class PageFinished(val url: String, val title: String) : BrowserUiIntent
    data object AdBlocked : BrowserUiIntent
    data object CheckRuleUpdates : BrowserUiIntent
    data class UpdateSearchQuery(val query: String) : BrowserUiIntent
    data class SelectSearchEngine(val engine: SearchEngine) : BrowserUiIntent
    data class ToggleSearchSheet(val visible: Boolean) : BrowserUiIntent
    data class ToggleBentoMenu(val visible: Boolean) : BrowserUiIntent
    data class SubmitSearch(val query: String) : BrowserUiIntent
    data class UpdateBottomBarVisibility(val visible: Boolean) : BrowserUiIntent
}

sealed interface BrowserUiEffect {
    data class ShowToast(val message: String) : BrowserUiEffect
    data class OpenExternal(val url: String) : BrowserUiEffect
    data class NavigateUrl(val url: String) : BrowserUiEffect
}
