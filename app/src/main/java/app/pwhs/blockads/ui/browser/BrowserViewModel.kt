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

class BrowserViewModel(
    application: Application,
    private val ruleRepository: BrowserRuleRepository
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
    }

    fun processIntent(intent: BrowserUiIntent) {
        when (intent) {
            is BrowserUiIntent.LoadUrl -> {
                val targetUrl = BrowserAdBlocker.sanitizeSearchUrl(intent.url)
                _uiState.update {
                    it.copy(
                        currentUrl = targetUrl,
                        displayUrl = targetUrl,
                        showShortcuts = false
                    )
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
