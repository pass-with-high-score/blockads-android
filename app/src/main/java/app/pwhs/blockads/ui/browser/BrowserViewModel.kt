package app.pwhs.blockads.ui.browser

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.ui.browser.interceptor.BrowserAdBlocker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BrowserViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private val _uiEffect = Channel<BrowserUiEffect>(Channel.BUFFERED)
    val uiEffect = _uiEffect.receiveAsFlow()

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
