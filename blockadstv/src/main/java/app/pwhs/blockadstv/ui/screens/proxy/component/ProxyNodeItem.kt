package app.pwhs.blockadstv.ui.screens.proxy.component

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.entities.ProxyProfile
import app.pwhs.blockadstv.ui.theme.AccentBlue
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import app.pwhs.blockadstv.utils.NodePingResult

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxyNodeItem(
    profile: ProxyProfile,
    isActive: Boolean,
    pingResult: NodePingResult?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> MaterialTheme.colorScheme.surfaceVariant
                    isActive -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surface
                }
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
            // Left info: Name, protocol, server
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) NeonGreen else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentBlue.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = profile.protocolBadgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentBlue,
                        )
                    }
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NeonGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.proxy_active_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonGreen,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Text(
                    text = "${profile.server}:${profile.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }

            // Right actions: Ping result & buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Ping status badge
                PingBadge(result = pingResult)
                Spacer(modifier = Modifier.width(10.dp))

                // Ping action button
                TvSmallButton(
                    iconRes = R.drawable.ic_settings_auto_update,
                    contentDescription = "Ping",
                    onClick = onPing,
                )
                Spacer(modifier = Modifier.width(8.dp))

                // Delete action button
                TvSmallButton(
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
private fun PingBadge(result: NodePingResult?) {
    when (result) {
        is NodePingResult.Testing -> {
            Text(
                text = "...",
                style = MaterialTheme.typography.labelMedium,
                color = AccentBlue,
            )
        }
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
        is NodePingResult.Timeout -> {
            Text(
                text = "Timeout",
                style = MaterialTheme.typography.labelMedium,
                color = DangerRed,
            )
        }
        is NodePingResult.Error -> {
            Text(
                text = "Err",
                style = MaterialTheme.typography.labelMedium,
                color = DangerRed,
            )
        }
        null -> Unit
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSmallButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                width = if (isFocused) 1.5.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
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
