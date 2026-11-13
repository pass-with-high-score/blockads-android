package app.pwhs.blockadstv.ui.screens.companion

import androidx.compose.ui.graphics.ImageBitmap

data class WebCompanionUiState(
    val isRunning: Boolean = false,
    val url: String? = null,
    val qrBitmap: ImageBitmap? = null,
    val lastActivity: String? = null,
    val error: String? = null,
)

sealed interface WebCompanionUiIntent {
    data object StartServer : WebCompanionUiIntent
    data object StopServer : WebCompanionUiIntent
    data object RestartServer : WebCompanionUiIntent
}
