package app.pwhs.blockads.ui.home

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.NeonGreen
import app.pwhs.blockads.ui.theme.TextSecondary
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import org.koin.androidx.compose.koinViewModel

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRequestVpnPermission: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val vpnEnabled by viewModel.vpnEnabled.collectAsState()
    val vpnConnecting by viewModel.vpnConnecting.collectAsState()
    val blockedCount by viewModel.blockedCount.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val filterLoadFailed by viewModel.filterLoadFailed.collectAsState()
    val recentBlocked by viewModel.recentBlocked.collectAsState()
    val hourlyStats by viewModel.hourlyStats.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.preloadFilter()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = NeonGreen,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Loading filters…",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    } else if (filterLoadFailed) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(DangerRed.copy(alpha = 0.1f))
                                .clickable { viewModel.retryLoadFilter() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = DangerRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Filter load failed · Tap to retry",
                                style = MaterialTheme.typography.bodySmall,
                                color = DangerRed,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = "https://adblock.turtlecute.org/".toUri()
                        }
                        context.startActivity(intent)
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = stringResource(R.string.test_block_ads),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

        // Status text
        Text(
            text = when {
                vpnConnecting -> stringResource(R.string.status_connecting)
                vpnEnabled -> stringResource(R.string.status_protected)
                else -> stringResource(R.string.status_unprotected)
            },
            style = MaterialTheme.typography.headlineMedium,
            color = when {
                vpnConnecting -> AccentBlue
                vpnEnabled -> NeonGreen
                else -> DangerRed
            },
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                vpnConnecting -> stringResource(R.string.home_connecting_desc)
                vpnEnabled -> stringResource(R.string.home_protected_desc)
                else -> stringResource(R.string.home_unprotected_desc)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Power button — never blocked by filter loading
        PowerButton(
            isActive = vpnEnabled,
            isConnecting = vpnConnecting,
            onClick = {
                if (!vpnConnecting) {
                    if (vpnEnabled) {
                        viewModel.stopVpn(context)
                    } else {
                        onRequestVpnPermission()
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(36.dp))

        // Stats cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.QueryStats,
                label = stringResource(R.string.total_queries),
                value = formatCount(totalCount),
                color = MaterialTheme.colorScheme.secondary
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Block,
                label = stringResource(R.string.blocked_queries),
                value = formatCount(blockedCount),
                color = DangerRed
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Block rate card
        val blockRate = if (totalCount > 0) (blockedCount * 100f / totalCount) else 0f
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = NeonGreen,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_block_rate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "${String.format("%.1f", blockRate)}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.home_filter_rules),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = formatCount(viewModel.domainCount),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeonGreen
                    )
                }
            }
        }

        // 24h Activity Chart
        if (hourlyStats.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "24-HOUR ACTIVITY",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                StatsChart(
                    stats = hourlyStats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(16.dp)
                )
            }
        }

        // Recent blocked domains
        if (recentBlocked.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.home_recent_blocked),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    recentBlocked.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(DangerRed)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = entry.domain,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = formatTimeSince(entry.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

            Spacer(modifier = Modifier.height(200.dp))
        }
    }
}

@Composable
private fun StatsChart(
    stats: List<app.pwhs.blockads.data.HourlyStat>,
    modifier: Modifier = Modifier
) {
    val totalColor = AccentBlue.copy(alpha = 0.4f)
    val blockedColor = DangerRed.copy(alpha = 0.7f)
    val labelColor = TextSecondary

    Canvas(modifier = modifier) {
        if (stats.isEmpty()) return@Canvas

        val barCount = stats.size
        val maxVal = (stats.maxOfOrNull { it.total } ?: 1).coerceAtLeast(1)
        val barWidth = size.width / barCount.toFloat()
        val chartHeight = size.height - 16f // leave room for bottom labels

        stats.forEachIndexed { index, stat ->
            val x = index * barWidth

            // Total bar
            val totalBarHeight = (stat.total.toFloat() / maxVal) * chartHeight
            drawRoundRect(
                color = totalColor,
                topLeft = androidx.compose.ui.geometry.Offset(x + 1f, chartHeight - totalBarHeight),
                size = androidx.compose.ui.geometry.Size(barWidth - 2f, totalBarHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )

            // Blocked bar (overlaid)
            val blockedBarHeight = (stat.blocked.toFloat() / maxVal) * chartHeight
            drawRoundRect(
                color = blockedColor,
                topLeft = androidx.compose.ui.geometry.Offset(x + 1f, chartHeight - blockedBarHeight),
                size = androidx.compose.ui.geometry.Size(barWidth - 2f, blockedBarHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
        }
    }
}

@Composable
private fun PowerButton(
    isActive: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnecting -> AccentBlue
            isActive -> NeonGreen
            else -> DangerRed
        },
        animationSpec = tween(500),
        label = "buttonColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive || isConnecting) 1f else 0.95f,
        animationSpec = tween(300),
        label = "scale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = when {
            isConnecting -> 0.3f
            isActive -> 0.4f
            else -> 0.2f
        },
        animationSpec = tween(500),
        label = "glow"
    )

    // Pulsing animation when active
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive && !isConnecting) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Spinning animation when connecting
    val connectingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isConnecting) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "connectingRotation"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(180.dp)
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    )
                )
        )

        // Main button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(140.dp)
                .scale(scale)
                .shadow(
                    elevation = if (isActive || isConnecting) 20.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = buttonColor.copy(alpha = 0.3f),
                    spotColor = buttonColor.copy(alpha = 0.3f)
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = 0.2f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .border(
                    width = 3.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            buttonColor,
                            buttonColor.copy(alpha = 0.5f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isConnecting
                ) { onClick() }
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    color = buttonColor,
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Toggle VPN",
                    tint = buttonColor,
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format("%.1fK", count / 1_000f)
        else -> count.toString()
    }
}

private fun formatTimeSince(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}

