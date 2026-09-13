package app.pwhs.blockads.ui.browser

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import app.pwhs.blockads.ui.browser.rules.BrowserRuleRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import app.pwhs.blockads.ui.browser.data.SearchEngine
import app.pwhs.blockads.ui.browser.data.SearchSuggestionRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(FlowPreview::class)
class BrowserViewModel(
    application: Application,
    private val ruleRepository: BrowserRuleRepository,
    private val suggestionRepository: SearchSuggestionRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        BrowserUiState(
            ruleVersion = ruleRepository.currentRules.value.version,
            ruleDomainsCount = ruleRepository.currentRules.value.adDomains.ifEmpty {
                listOf(BrowserAdBlocker.domainsCount.toString())
            }.size
        )
    )
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _uiEffect = Channel<BrowserUiEffect>(Channel.BUFFERED)
    val uiEffect = _uiEffect.receiveAsFlow()

    init {
        viewModelScope.launch {
            ruleRepository.currentRules.collect { pkg ->
                _uiState.update {
                    it.copy(
                        ruleVersion = pkg.version,
                        ruleDomainsCount = if (pkg.adDomains.isNotEmpty()) pkg.adDomains.size else BrowserAdBlocker.domainsCount
                    )
                }
            }
        }

        // Live search autocomplete debounced at 250ms
        viewModelScope.launch {
            _uiState
                .map { it.searchQuery }
                .distinctUntilChanged()
                .debounce(250)
                .collect { query ->
                    if (query.length >= 2) {
                        val suggestions = suggestionRepository.getSuggestions(query)
                        _uiState.update { it.copy(suggestions = suggestions) }
                    } else {
                        _uiState.update { it.copy(suggestions = emptyList()) }
                    }
                }
        }
    }

    fun processIntent(intent: BrowserUiIntent) {
        when (intent) {
            is BrowserUiIntent.LoadUrl -> {
                val targetUrl = resolveUrl(intent.url, _uiState.value.selectedSearchEngine)
                _uiState.update {
                    it.copy(
                        currentUrl = targetUrl,
                        displayUrl = targetUrl,
                        showShortcuts = false,
                        isSearchSheetVisible = false
                    )
                }
                viewModelScope.launch {
                    _uiEffect.send(BrowserUiEffect.NavigateUrl(targetUrl))
                }
            }
            is BrowserUiIntent.Reload -> {
                _uiState.update { it.copy(currentUrl = it.displayUrl) }
            }
            is BrowserUiIntent.GoBack -> {
                // Navigation handled by WebView directly
            }
            is BrowserUiIntent.GoForward -> {
                // Navigation handled by WebView directly
            }
            is BrowserUiIntent.ToggleDesktopMode -> {
                _uiState.update { it.copy(isDesktopMode = !it.isDesktopMode) }
            }
            is BrowserUiIntent.ToggleAdBlock -> {
                _uiState.update { it.copy(adBlockEnabled = !it.adBlockEnabled) }
            }
            is BrowserUiIntent.ToggleShortcuts -> {
                _uiState.update { it.copy(showShortcuts = !it.showShortcuts) }
            }
            is BrowserUiIntent.ClearData -> {
                clearBrowsingData()
            }
            is BrowserUiIntent.UpdateProgress -> {
                _uiState.update {
                    it.copy(
                        progress = intent.progress,
                        isLoading = intent.progress in 1..99
                    )
                }
            }
            is BrowserUiIntent.PageStarted -> {
                _uiState.update {
                    it.copy(
                        displayUrl = intent.url,
                        isLoading = true
                    )
                }
            }
            is BrowserUiIntent.PageFinished -> {
                _uiState.update {
                    it.copy(
                        displayUrl = intent.url,
                        pageTitle = intent.title.ifEmpty { intent.url },
                        isLoading = false
                    )
                }
            }
            is BrowserUiIntent.AdBlocked -> {
                _uiState.update { it.copy(blockedCount = it.blockedCount + 1) }
            }
            is BrowserUiIntent.CheckRuleUpdates -> {
                checkForRuleUpdates()
            }
            is BrowserUiIntent.UpdateSearchQuery -> {
                _uiState.update { it.copy(searchQuery = intent.query) }
            }
            is BrowserUiIntent.SelectSearchEngine -> {
                _uiState.update { it.copy(selectedSearchEngine = intent.engine) }
            }
            is BrowserUiIntent.ToggleSearchSheet -> {
                _uiState.update {
                    it.copy(
                        isSearchSheetVisible = intent.visible,
                        searchQuery = if (intent.visible) it.displayUrl else "",
                        suggestions = emptyList()
                    )
                }
            }
            is BrowserUiIntent.SubmitSearch -> {
                val targetUrl = resolveUrl(intent.query, _uiState.value.selectedSearchEngine)
                _uiState.update {
                    it.copy(
                        currentUrl = targetUrl,
                        displayUrl = targetUrl,
                        isSearchSheetVisible = false,
                        showShortcuts = false
                    )
                }
                viewModelScope.launch {
                    _uiEffect.send(BrowserUiEffect.NavigateUrl(targetUrl))
                }
            }
            is BrowserUiIntent.ToggleBentoMenu -> {
                _uiState.update { it.copy(isBentoMenuVisible = intent.visible) }
            }
            is BrowserUiIntent.UpdateBottomBarVisibility -> {
                _uiState.update { it.copy(isBottomBarVisible = intent.visible) }
            }
        }
    }

    private fun resolveUrl(input: String, engine: SearchEngine): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> engine.buildSearchUrl(trimmed)
        }
    }

    private fun checkForRuleUpdates() {
        if (_uiState.value.isCheckingRuleUpdates) return

        _uiState.update { it.copy(isCheckingRuleUpdates = true) }
        viewModelScope.launch {
            val result = ruleRepository.checkAndUpdate()
            _uiState.update { it.copy(isCheckingRuleUpdates = false) }

            result.fold(
                onSuccess = { updated ->
                    val message = if (updated) {
                        "Đã cập nhật bộ lọc lên phiên bản v${_uiState.value.ruleVersion} (${_uiState.value.ruleDomainsCount} tên miền)"
                    } else {
                        "Bộ lọc trình duyệt đã ở phiên bản mới nhất (v${_uiState.value.ruleVersion})"
                    }
                    _uiEffect.send(BrowserUiEffect.ShowToast(message))
                },
                onFailure = { error ->
                    _uiEffect.send(
                        BrowserUiEffect.ShowToast("Không thể tải bản cập nhật: ${error.localizedMessage ?: "Lỗi kết nối"}")
                    )
                }
            )
        }
    }

    private fun clearBrowsingData() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            viewModelScope.launch {
                _uiEffect.send(BrowserUiEffect.ShowToast("Đã xóa cookie & bộ nhớ đệm"))
            }
        } catch (e: Exception) {
            viewModelScope.launch {
                _uiEffect.send(BrowserUiEffect.ShowToast("Lỗi khi xóa dữ liệu: ${e.message}"))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Auto-clean incognito cookies on session end
        if (_uiState.value.isIncognito) {
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
}
