package app.pwhs.blockadstv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.service.VpnState
import app.pwhs.blockadstv.ui.theme.AccentBlue
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.NeonGreenDim
import app.pwhs.blockadstv.ui.theme.TextSecondary
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: TvHomeViewModel = koinViewModel(),
    onRequestVpnPermission: () -> Unit = {},
    onStopVpn: () -> Unit = {},
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val vpnEnabled by viewModel.vpnEnabled.collectAsStateWithLifecycle()
    val vpnConnecting by viewModel.vpnConnecting.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val securityThreats by viewModel.securityThreats.collectAsStateWithLifecycle()
    val uptimeMs by viewModel.protectionUptimeMs.collectAsStateWithLifecycle()

    val blockRate = if (totalCount > 0) (blockedCount * 100f / totalCount) else 0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
    ) {
        // Status header
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = when {
                    vpnEnabled -> NeonGreen
                    vpnConnecting -> AccentBlue
                    else -> DangerRed
                },
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = when (vpnState) {
                    VpnState.RUNNING -> "Protected"
                    VpnState.STARTING -> "Connecting..."
                    VpnState.STOPPING -> "Disconnecting..."
                    VpnState.STOPPED -> "Unprotected"
                },
                style = MaterialTheme.typography.headlineLarge,
                color = when {
                    vpnEnabled -> NeonGreen
                    vpnConnecting -> AccentBlue
                    else -> DangerRed
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                vpnEnabled -> "Your device is protected from ads and trackers"
                vpnConnecting -> "Loading filters and establishing tunnel..."
                else -> "Turn on protection to block ads and trackers"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Power button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PowerButton(
                vpnEnabled = vpnEnabled,
                vpnConnecting = vpnConnecting,
                onClick = {
                    if (vpnEnabled) {
                        onStopVpn()
                    } else if (!vpnConnecting) {
                        onRequestVpnPermission()
                    }
                },
            )

            Spacer(modifier = Modifier.width(40.dp))

            Column {
                Text(
                    text = when {
                        vpnConnecting -> "CONNECTING..."
                        vpnEnabled -> "TAP TO DISABLE"
                        else -> "TAP TO ENABLE"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Local VPN Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Stats grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCard(
                icon = Icons.Default.QueryStats,
                label = "Total Queries",
                value = formatCount(totalCount),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                icon = Icons.Default.Block,
                label = "Blocked",
                value = formatCount(blockedCount),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                icon = Icons.Default.Security,
                label = "Threats",
                value = formatCount(securityThreats),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatCard(
                icon = Icons.Default.Shield,
                label = "Block Rate",
                value = String.format(Locale.getDefault(), "%.1f%%", blockRate),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                icon = Icons.Default.Timer,
                label = "Uptime",
                value = formatUptime(uptimeMs),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", count / 1_000_000f)
    count >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", count / 1_000f)
    else -> count.toString()
}

private fun formatUptime(millis: Long): String {
    if (millis <= 0) return "--:--"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PowerButton(
    vpnEnabled: Boolean,
    vpnConnecting: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    val bgColor = when {
        vpnEnabled && isFocused -> NeonGreen
        vpnEnabled -> NeonGreenDim
        vpnConnecting -> AccentBlue.copy(alpha = 0.3f)
        isFocused -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = Modifier
            .size(120.dp)
            .clip(CircleShape)
            .background(bgColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                    onClick()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = if (vpnEnabled) "Turn off" else "Turn on",
            modifier = Modifier.size(56.dp),
            tint = if (vpnEnabled) {
                MaterialTheme.colorScheme.background
            } else {
                TextSecondary
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
