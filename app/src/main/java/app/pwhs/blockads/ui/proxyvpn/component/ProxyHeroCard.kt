package app.pwhs.blockads.ui.proxyvpn.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import app.pwhs.blockads.R
import app.pwhs.blockads.utils.CurrentNetworkType

@Composable
fun ProxyHeroCard(
    isConnected: Boolean,
    onToggleConnection: () -> Unit,
    serverName: String,
    countryCode: String?,
    protocol: String,
    port: Int?,
    serverHost: String?,
    latencyMs: Long?,
    networkName: String? = null,
    networkType: CurrentNetworkType = CurrentNetworkType.WIFI,
    currentIp: String? = null,
    isRefreshingIp: Boolean = false,
    onRefreshIp: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isConnected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    val borderColor = if (isConnected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }

    val titleColor = if (isConnected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val bodyTextColor = if (isConnected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val iconTint = if (isConnected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
    val rotation by if (isRefreshingIp) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "spin_angle"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            // Subtle watermark flag in background
            val flagRes = countryCode?.let { CountryFlagRegistry.getFlagResId(it) }
            if (flagRes != null) {
                Image(
                    painter = painterResource(id = flagRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(110.dp)
                        .align(Alignment.CenterEnd)
                        .offset(x = 18.dp)
                        .alpha(0.08f)
                        .clip(CircleShape)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Details
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Top Badges Row: Status + Protocol pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ON / OFF Status Badge
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isConnected) {
                                    stringResource(R.string.proxy_hero_status_on)
                                } else {
                                    stringResource(R.string.proxy_hero_status_off)
                                } ,
                                color = if (isConnected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        // Protocol & Port Pill
                        if (protocol.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = if (port != null && port > 0) "$protocol $port" else protocol,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Prominent Public IP Row (Above Server Name)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = if (isConnected) MaterialTheme.colorScheme.primary else iconTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isRefreshingIp && currentIp.isNullOrBlank()) {
                                stringResource(R.string.proxy_refreshing_ip)
                            } else if (!currentIp.isNullOrBlank()) {
                                "IP: $currentIp"
                            } else {
                                "IP: ---"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = titleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (onRefreshIp != null) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = !isRefreshingIp) { onRefreshIp() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.proxy_refresh_ip),
                                    tint = iconTint,
                                    modifier = Modifier
                                        .size(15.dp)
                                        .graphicsLayer { rotationZ = rotation }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Server / Location Name
                    Text(
                        text = serverName.ifEmpty { stringResource(R.string.proxy_hero_no_server_selected) },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Subtitle: Host + Ping
                    val hasHost = !serverHost.isNullOrBlank()
                    val hasLatency = latencyMs != null && latencyMs > 0
                    if (hasHost || hasLatency || isConnected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (hasHost) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = serverHost.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bodyTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (hasLatency) {
                                val pingColor = if (latencyMs < 150) Color(0xFF10B981) else Color(0xFFF59E0B)
                                Text(
                                    text = if (hasHost) "• $latencyMs ms" else "$latencyMs ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = pingColor,
                                    fontWeight = FontWeight.Bold
                                )
                            } else if (isConnected) {
                                Text(
                                    text = if (hasHost) "• ${stringResource(R.string.proxy_hero_connected_desc)}" else stringResource(R.string.proxy_hero_connected_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isConnected) MaterialTheme.colorScheme.primary else Color(0xFF10B981),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Network Info Row
                    if (!networkName.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val netIcon = when (networkType) {
                                CurrentNetworkType.WIFI -> Icons.Default.Wifi
                                CurrentNetworkType.CELLULAR -> Icons.Default.SignalCellularAlt
                                CurrentNetworkType.ETHERNET -> Icons.Default.Lan
                                CurrentNetworkType.DISCONNECTED -> Icons.Default.CloudOff
                            }
                            Icon(
                                imageVector = netIcon,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = networkName,
                                style = MaterialTheme.typography.bodySmall,
                                color = bodyTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Right Side: Large Circular Power Button
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnected) Color(0xFF10B981).copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = if (isConnected) Color(0xFF10B981) else MaterialTheme.colorScheme.surface,
                        border = if (isConnected) null else BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = if (isConnected) 4.dp else 1.dp,
                        onClick = onToggleConnection
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = if (isConnected) "Disconnect" else "Connect",
                                tint = if (isConnected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
