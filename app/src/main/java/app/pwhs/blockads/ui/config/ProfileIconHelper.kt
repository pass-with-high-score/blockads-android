package app.pwhs.blockads.ui.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

data class ProfileIconItem(
    val id: String,
    val icon: ImageVector,
    val label: String
)

object ProfileIconHelper {
    const val DEFAULT_ICON_ID = "shield"

    val PRESET_ICONS = listOf(
        ProfileIconItem("shield", Icons.Default.Shield, "Shield"),
        ProfileIconItem("security", Icons.Default.Security, "Security"),
        ProfileIconItem("lock", Icons.Default.Lock, "Privacy"),
        ProfileIconItem("bolt", Icons.Default.Bolt, "Lightning"),
        ProfileIconItem("speed", Icons.Default.Speed, "Speed"),
        ProfileIconItem("rocket", Icons.Default.RocketLaunch, "Rocket"),
        ProfileIconItem("gaming", Icons.Default.SportsEsports, "Gaming"),
        ProfileIconItem("wifi", Icons.Default.Wifi, "Wi-Fi"),
        ProfileIconItem("globe", Icons.Default.Public, "Global"),
        ProfileIconItem("home", Icons.Default.Home, "Home"),
        ProfileIconItem("work", Icons.Default.Work, "Work"),
        ProfileIconItem("school", Icons.Default.School, "School"),
        ProfileIconItem("night", Icons.Default.Bedtime, "Night"),
        ProfileIconItem("star", Icons.Default.Star, "Favorite"),
        ProfileIconItem("cloud", Icons.Default.Cloud, "Cloud"),
        ProfileIconItem("vpn_key", Icons.Default.VpnKey, "VPN"),
        ProfileIconItem("tune", Icons.Default.Tune, "Custom"),
        ProfileIconItem("favorite", Icons.Default.Favorite, "Heart"),
        ProfileIconItem("movie", Icons.Default.Movie, "Streaming"),
        ProfileIconItem("terminal", Icons.Default.Terminal, "Developer")
    )

    fun getIcon(id: String?): ImageVector {
        if (id.isNullOrBlank()) return Icons.Default.Shield
        return PRESET_ICONS.firstOrNull { it.id.equals(id, ignoreCase = true) }?.icon ?: Icons.Default.Shield
    }
}
