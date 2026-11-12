package app.pwhs.blockadstv.ui.screens.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockadstv.companion.LocalWebCompanionServer
import app.pwhs.blockadstv.companion.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebCompanionViewModel(
    private val server: LocalWebCompanionServer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebCompanionUiState())
    val uiState: StateFlow<WebCompanionUiState> = _uiState.asStateFlow()

    private var lastQrUrl: String? = null

    init {
        viewModelScope.launch {
            server.status.collect { status ->
                val url = status.url
                val bitmap = if (url != null && url != lastQrUrl) {
                    lastQrUrl = url
                    withContext(Dispatchers.Default) {
                        QrCodeGenerator.generateQrBitmap(url, sizePx = 480)
                    }
                } else if (url == null) {
                    lastQrUrl = null
                    null
                } else {
                    _uiState.value.qrBitmap
                }

                _uiState.update {
                    it.copy(
                        isRunning = status.isRunning,
                        url = url,
                        qrBitmap = bitmap,
                        lastActivity = status.lastActivity,
                        error = status.error,
                    )
                }
            }
        }
    }

    fun onIntent(intent: WebCompanionUiIntent) {
        when (intent) {
            is WebCompanionUiIntent.StartServer -> server.start()
            is WebCompanionUiIntent.StopServer -> server.stop()
            is WebCompanionUiIntent.RestartServer -> {
                server.stop()
                server.start()
            }
        }
    }

    override fun onCleared() {
        server.stop()
        super.onCleared()
    }
}
