package app.pwhs.blockads.ui.httpsfiltering

import android.graphics.drawable.Drawable
import java.io.File

/** Represents an installed browser detected on the device. */
data class BrowserInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val icon: Drawable?,
    val isSelected: Boolean = false
)

/** Certificate installation verification status. */
enum class CertStatus {
    /** Not yet checked. */
    UNKNOWN,
    /** Verification in progress. */
    CHECKING,
    /** Certificate is installed and working. */
    INSTALLED,
    /** Certificate is NOT installed or verification failed. */
    NOT_INSTALLED
}

sealed class HttpsFilteringEvent {
    /** CA cert saved to Downloads — show manual install instructions. */
    data class CaCertSavedToDownloads(val fileName: String) : HttpsFilteringEvent()

    /** Fallback: cert saved to cache for legacy intent install. */
    data class CaCertExportedLegacy(val certFile: File) : HttpsFilteringEvent()

    data class Error(val message: String) : HttpsFilteringEvent()
    data object ProxyStarted : HttpsFilteringEvent()
    data object ProxyStopped : HttpsFilteringEvent()
    /** WireGuard routing was turned off because HTTPS filtering was enabled. */
    data object WireGuardDisabledForHttps : HttpsFilteringEvent()
}
