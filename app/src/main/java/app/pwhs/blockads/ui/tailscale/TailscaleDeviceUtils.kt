package app.pwhs.blockads.ui.tailscale

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

object TailscaleDeviceUtils {

    fun getDeviceIcon(os: String): ImageVector {
        val lower = os.lowercase().trim()
        return when {
            lower.contains("android") -> Icons.Filled.Android
            lower.contains("ios") -> Icons.Filled.PhoneIphone
            lower.contains("darwin") || lower.contains("mac") -> Icons.Filled.DesktopMac
            lower.contains("windows") || lower.contains("win") -> Icons.Filled.DesktopWindows
            lower.contains("linux") || lower.contains("bsd") -> Icons.Filled.Terminal
            else -> Icons.Filled.Computer
        }
    }

    fun formatDeviceOs(os: String): String {
        val lower = os.lowercase().trim()
        return when {
            lower.contains("android") -> "Android"
            lower.contains("ios") -> "iOS"
            lower.contains("darwin") || lower.contains("mac") -> "macOS"
            lower.contains("windows") || lower.contains("win") -> "Windows"
            lower.contains("linux") -> "Linux"
            lower.contains("bsd") -> "BSD"
            os.isNotBlank() -> os.replaceFirstChar { it.uppercase() }
            else -> "Unknown"
        }
    }
}
