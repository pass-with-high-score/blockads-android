package app.pwhs.blockads.ui.proxyvpn.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.theme.BlockadsTheme
import app.pwhs.blockads.utils.CurrentNetworkType
import kotlinx.coroutines.delay

private const val PROBE_START_HOLD_MS = 5000L
private val COOKIE_12_DECORATION_SHAPE = MorphingCookieShape(progress = 1f, lobes = 12, depth = 0.06f)

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
    // Hold skeleton state briefly after connection starts so transient probe delays do not flash "IP: ---".
    var wasConnected by remember { mutableStateOf(isConnected) }
    var probeHoldActive by remember { mutableStateOf(false) }
    LaunchedEffect(isConnected) {
        if (isConnected && !wasConnected) {
            wasConnected = true
            probeHoldActive = true
            delay(PROBE_START_HOLD_MS)
            probeHoldActive = false
        } else {
            wasConnected = isConnected
            probeHoldActive = false
        }
    }

    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (isConnected) colors.surface else colors.surfaceVariant.copy(alpha = 0.45f),
        animationSpec = tween(300),
        label = "hero_container"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isConnected) colors.primary.copy(alpha = 0.35f) else colors.outlineVariant.copy(alpha = 0.4f),
        animationSpec = tween(300),
        label = "hero_border"
    )
    val decorationProgress by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 300f),
        label = "cookie12_decoration"
    )
    val decorationTint = lerp(
        colors.surfaceVariant.copy(alpha = 0.55f),
        colors.primary.copy(alpha = 0.14f),
        decorationProgress.coerceIn(0f, 1f)
    )

    val titleColor = colors.onSurface
    val bodyTextColor = colors.onSurfaceVariant
    val iconTint = colors.onSurfaceVariant

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val decorationScale = 1f + 0.12f * decorationProgress
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .align(Alignment.CenterEnd)
                    .offset(x = 36.dp)
                    .graphicsLayer {
                        scaleX = decorationScale
                        scaleY = decorationScale
                        rotationZ = 30f * decorationProgress
                    }
                    .clip(COOKIE_12_DECORATION_SHAPE)
                    .background(decorationTint)
            )

            val flagRes = countryCode?.let { CountryFlagRegistry.getFlagResId(it) }
            if (flagRes != null) {
                Image(
                    painter = painterResource(id = flagRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(84.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = (-18).dp, y = 14.dp)
                        .alpha(0.06f)
                        .clip(CircleShape)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                HeroStatusHeaderRow(isConnected = isConnected)

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Clover4LeafRefreshBadge(
                            isConnected = isConnected,
                            isRefreshingIp = isRefreshingIp,
                            countryCode = countryCode,
                            iconTint = iconTint,
                            onRefreshIp = { onRefreshIp?.invoke() }
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            HeroIpValue(
                                currentIp = currentIp,
                                isRefreshingIp = isRefreshingIp,
                                probeHoldActive = probeHoldActive,
                                titleColor = titleColor
                            )
                            HeroProtocolPill(
                                isConnected = isConnected,
                                protocol = protocol,
                                port = port
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))
                    MorphingStartToggle(isConnected = isConnected, onToggleConnection = onToggleConnection)
                }

                Spacer(modifier = Modifier.height(14.dp))

                HeroMetadataFooter(
                    isConnected = isConnected,
                    serverName = serverName,
                    serverHost = serverHost,
                    latencyMs = latencyMs,
                    networkName = networkName,
                    networkType = networkType,
                    titleColor = titleColor,
                    bodyTextColor = bodyTextColor,
                    iconTint = iconTint
                )
            }
        }
    }
}

@Composable
private fun HeroStatusHeaderRow(isConnected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val dotColor = if (isConnected) Color(0xFF22C55E) else colors.onSurfaceVariant.copy(alpha = 0.55f)
    val pillColor = if (isConnected) Color(0xFF86EFAC) else colors.surfaceVariant
    val pillTextColor = if (isConnected) Color(0xFF052E16) else colors.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Public,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Surface(
            shape = CircleShape,
            color = pillColor
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = stringResource(if (isConnected) R.string.proxy_hero_status_on else R.string.proxy_hero_status_off),
                    color = pillTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun HeroIpValue(
    currentIp: String?,
    isRefreshingIp: Boolean,
    probeHoldActive: Boolean,
    titleColor: Color
) {
    val colors = MaterialTheme.colorScheme
    val showSkeleton = (isRefreshingIp || probeHoldActive) && currentIp.isNullOrBlank()
    Box(
        modifier = Modifier.heightIn(min = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (showSkeleton) {
            WallClockSkeletonPill(
                width = 132.dp,
                height = 20.dp,
                color = colors.surfaceContainerHighest
            )
        } else {
            Text(
                text = currentIp?.takeIf { it.isNotBlank() } ?: "---",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontFeatureSettings = "tnum"
                ),
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeroProtocolPill(isConnected: Boolean, protocol: String, port: Int?) {
    if (protocol.isBlank()) return
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHigh.copy(alpha = 0.9f),
        border = BorderStroke(
            1.dp,
            if (isConnected) colors.primary.copy(alpha = 0.25f) else colors.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (port != null && port > 0) "$protocol $port" else protocol,
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun HeroMetadataFooter(
    isConnected: Boolean,
    serverName: String,
    serverHost: String?,
    latencyMs: Long?,
    networkName: String?,
    networkType: CurrentNetworkType,
    titleColor: Color,
    bodyTextColor: Color,
    iconTint: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (!networkName.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val netIcon = when (networkType) {
                        CurrentNetworkType.WIFI -> Icons.Default.Wifi
                        CurrentNetworkType.CELLULAR -> Icons.Default.SignalCellularAlt
                        CurrentNetworkType.ETHERNET -> Icons.Default.Lan
                        CurrentNetworkType.DISCONNECTED -> Icons.Default.CloudOff
                    }
                    Icon(netIcon, contentDescription = null, tint = iconTint, modifier = Modifier.size(12.dp))
                    Text(
                        text = networkName,
                        style = MaterialTheme.typography.bodySmall,
                        color = bodyTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = serverName.ifEmpty { stringResource(R.string.proxy_hero_no_server_selected) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val hasHost = !serverHost.isNullOrBlank()
        val hasLatency = latencyMs != null && latencyMs > 0
        if (hasHost || hasLatency || isConnected) {
            Column(modifier = Modifier.weight(1.2f)) {
                if (hasHost) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = iconTint, modifier = Modifier.size(12.dp))
                        Text(
                            text = serverHost.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = bodyTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (hasLatency) {
                    val pingColor = if (latencyMs < 150) Color(0xFF10B981) else Color(0xFFF59E0B)
                    Text(
                        text = "$latencyMs ms",
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = pingColor,
                        fontWeight = FontWeight.Bold
                    )
                } else if (isConnected) {
                    Text(
                        text = stringResource(R.string.proxy_hero_connected_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProxyHeroCardLightPreview() {
    BlockadsTheme(themeMode = AppPreferences.THEME_LIGHT) {
        ProxyHeroCard(
            isConnected = true,
            onToggleConnection = {},
            serverName = "Tokyo Edge 01",
            countryCode = "JP",
            protocol = "VLESS",
            port = 443,
            serverHost = "jp01.example.net",
            latencyMs = 42,
            networkName = "Home Wi-Fi",
            currentIp = "203.0.113.42",
            onRefreshIp = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProxyHeroCardDarkPreview() {
    BlockadsTheme(themeMode = AppPreferences.THEME_DARK) {
        ProxyHeroCard(
            isConnected = false,
            onToggleConnection = {},
            serverName = "Singapore Core",
            countryCode = "SG",
            protocol = "WireGuard",
            port = 51820,
            serverHost = "sg.example.net",
            latencyMs = null,
            networkName = "5G",
            networkType = CurrentNetworkType.CELLULAR,
            currentIp = null,
            isRefreshingIp = true,
            onRefreshIp = {}
        )
    }
}
