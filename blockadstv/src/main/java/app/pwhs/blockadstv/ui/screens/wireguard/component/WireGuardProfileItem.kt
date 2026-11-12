package app.pwhs.blockadstv.ui.screens.wireguard.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.entities.WireGuardProfile
import app.pwhs.blockadstv.data.geoip.GeoIpLookup
import app.pwhs.blockadstv.ui.theme.AccentBlue
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import app.pwhs.blockadstv.utils.NodePingResult
import app.pwhs.blockadstv.utils.configIssue

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WireGuardProfileItem(
    profile: WireGuardProfile,
    isActive: Boolean,
    countryCode: String?,
    pingResult: NodePingResult?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val issue = profile.configIssue
    val endpoint = profile.config.peers.firstOrNull()?.endpoint ?: "No endpoint"
    val flag = if (!countryCode.isNullOrEmpty()) GeoIpLookup.countryCodeToEmoji(countryCode) else "🌐"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> MaterialTheme.colorScheme.surfaceVariant
                    isActive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surface
                },
            )
            .border(
                width = if (isFocused) 2.dp else if (isActive) 1.5.dp else 1.dp,
                color = when {
                    isFocused -> MaterialTheme.colorScheme.primary
                    isActive -> NeonGreen
                    else -> MaterialTheme.colorScheme.border
                },
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$flag ${profile.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) NeonGreen else MaterialTheme.colorScheme.onSurface,
                    )
                    if (profile.isAmnezia) {
                        Spacer(modifier = Modifier.width(8.dp))
                        BadgeChip(text = "AWG", color = AccentBlue)
                    }
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        BadgeChip(text = stringResource(R.string.proxy_active_badge), color = NeonGreen)
                    }
                }
                Text(
                    text = issue?.toDisplayMessage() ?: endpoint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (issue != null) DangerRed else TextSecondary,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                WgPingBadge(result = pingResult)
                Spacer(modifier = Modifier.width(10.dp))
                WgIconButton(
                    iconRes = R.drawable.ic_settings_auto_update,
                    contentDescription = "Ping",
                    onClick = onPing,
                )
                Spacer(modifier = Modifier.width(8.dp))
                WgIconButton(
                    iconRes = R.drawable.ic_setting,
                    contentDescription = "Rename",
                    onClick = onRename,
                )
                Spacer(modifier = Modifier.width(8.dp))
                WgIconButton(
                    iconRes = R.drawable.ic_settings_trash,
                    contentDescription = "Delete",
                    tint = DangerRed,
                    onClick = onDelete,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BadgeChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WgPingBadge(result: NodePingResult?) {
    when (result) {
        is NodePingResult.Testing -> Text(
            text = "...",
            style = MaterialTheme.typography.labelMedium,
            color = AccentBlue,
        )
        is NodePingResult.Success -> {
            val color = when {
                result.latencyMs < 100 -> NeonGreen
                result.latencyMs < 300 -> AccentBlue
                else -> DangerRed
            }
            Text(
                text = "${result.latencyMs}ms",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
        is NodePingResult.Timeout -> Text(
            text = "Timeout",
            style = MaterialTheme.typography.labelMedium,
            color = DangerRed,
        )
        is NodePingResult.Error -> Text(
            text = "Err",
            style = MaterialTheme.typography.labelMedium,
            color = DangerRed,
        )
        null -> Unit
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WgIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .border(
                width = if (isFocused) 1.5.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
