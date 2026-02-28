package app.pwhs.blockads.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.ProtectionProfile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", count / 1_000_000f)
    count >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", count / 1_000f)
    else -> count.toString()
}

fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun formatTimeSince(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}

fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun startOfDayMillis(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis


fun formatDataSize(kilobytes: Long): String = when {
    kilobytes < 1024 -> "$kilobytes KB"
    kilobytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", kilobytes / 1024f)
    else -> String.format(Locale.getDefault(), "%.1f GB", kilobytes / (1024f * 1024f))
}

fun formatUptimeShort(ms: Long): String {
    if (ms <= 0) return "—"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

val hourFormat = ThreadLocal.withInitial {
    SimpleDateFormat("HH", Locale.getDefault())
}

val dayFormat = ThreadLocal.withInitial {
    SimpleDateFormat("EEE", Locale.getDefault())
}


fun formatTime(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

@Composable
fun formatDays(daysOfWeek: String): String {
    val dayNames = listOf(
        stringResource(R.string.profile_day_mon),
        stringResource(R.string.profile_day_tue),
        stringResource(R.string.profile_day_wed),
        stringResource(R.string.profile_day_thu),
        stringResource(R.string.profile_day_fri),
        stringResource(R.string.profile_day_sat),
        stringResource(R.string.profile_day_sun)
    )
    val days = daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }
    return if (days.size == 7) stringResource(R.string.profile_schedule_every_day)
    else days.mapNotNull { if (it in 1..7) dayNames[it - 1] else null }.joinToString(", ")
}

fun profileIcon(type: String): ImageVector = when (type) {
    ProtectionProfile.TYPE_DEFAULT -> Icons.Default.GppGood
    ProtectionProfile.TYPE_STRICT -> Icons.Default.Security
    ProtectionProfile.TYPE_FAMILY -> Icons.Default.FamilyRestroom
    ProtectionProfile.TYPE_GAMING -> Icons.Default.SportsEsports
    else -> Icons.Default.Tune
}

@Composable
fun profileDescription(type: String): String = when (type) {
    ProtectionProfile.TYPE_DEFAULT -> stringResource(R.string.profile_desc_default)
    ProtectionProfile.TYPE_STRICT -> stringResource(R.string.profile_desc_strict)
    ProtectionProfile.TYPE_FAMILY -> stringResource(R.string.profile_desc_family)
    ProtectionProfile.TYPE_GAMING -> stringResource(R.string.profile_desc_gaming)
    else -> stringResource(R.string.profile_desc_custom)
}

@Composable
fun profileDisplayName(profile: ProtectionProfile): String = when (profile.profileType) {
    ProtectionProfile.TYPE_DEFAULT -> stringResource(R.string.profile_name_default)
    ProtectionProfile.TYPE_STRICT -> stringResource(R.string.profile_name_strict)
    ProtectionProfile.TYPE_FAMILY -> stringResource(R.string.profile_name_family)
    ProtectionProfile.TYPE_GAMING -> stringResource(R.string.profile_name_gaming)
    else -> profile.name
}
