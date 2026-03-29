package app.pwhs.blockads.ui.event

sealed interface UiEvent {
    data class ToastRes(val resId: Int, val args: List<Any> = emptyList()) : UiEvent
    data class ToastText(val message: String) : UiEvent
    data class ShareFile(val uri: android.net.Uri, val mimeType: String) : UiEvent
}