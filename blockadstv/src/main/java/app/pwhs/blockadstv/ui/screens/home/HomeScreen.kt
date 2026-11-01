package app.pwhs.blockadstv.ui.screens.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.R
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
    onRequestVpnPermission: () -> Unit,
    onStopVpn: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TvHomeViewModel = koinViewModel(),
) {
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()
    val securityThreats by viewModel.securityThreats.collectAsStateWithLifecycle()
    val uptimeMs by viewModel.protectionUptimeMs.collectAsStateWithLifecycle()
    val filterRuleCount by viewModel.filterRuleCount.collectAsStateWithLifecycle()
    val topBlocked by viewModel.topBlockedDomains.collectAsStateWithLifecycle()
    val routingMode by viewModel.routingMode.collectAsStateWithLifecycle()

    val vpnEnabled = vpnState == VpnState.RUNNING
    val vpnConnecting = vpnState == VpnState.STARTING || vpnState == VpnState.STOPPING
    val blockRate = if (totalCount > 0) (blockedCount.toFloat() / totalCount * 100) else 0f

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        // Status header
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(if (vpnEnabled) R.drawable.ic_shield_on else R.drawable.ic_shield_off),
                contentDescription = null,
                tint = when {
                    vpnEnabled -> NeonGreen
                    vpnConnecting -> AccentBlue
                    else -> DangerRed
                },
                modifier = Modifier.size(26.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = when (vpnState) {
                    VpnState.RUNNING -> stringResource(R.string.home_protected)
                    VpnState.STARTING -> "Connecting..."
                    VpnState.STOPPING -> "Disconnecting..."
                    VpnState.STOPPED -> stringResource(R.string.home_unprotected)
                },
                style = MaterialTheme.typography.headlineMedium,
                color = when {
                    vpnEnabled -> NeonGreen
                    vpnConnecting -> AccentBlue
                    else -> DangerRed
                },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = when (routingMode) {
                        app.pwhs.blockadstv.data.datastore.TvPreferences.ROUTING_MODE_ROOT -> "Root Proxy"
                        app.pwhs.blockadstv.data.datastore.TvPreferences.ROUTING_MODE_PROXY_VPN -> "Proxy VPN"
                        else -> "DNS VPN"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentBlue,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = when {
                vpnEnabled -> stringResource(R.string.home_protected_desc)
                vpnConnecting -> "Loading filters and establishing tunnel..."
                else -> stringResource(R.string.home_unprotected_desc)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Power button
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
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
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Stats grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                iconRes = R.drawable.ic_chart_bar,
                label = stringResource(R.string.home_total_queries),
                value = formatCount(totalCount),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                iconRes = R.drawable.ic_shield_off,
                label = stringResource(R.string.home_blocked),
                value = formatCount(blockedCount),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                iconRes = R.drawable.ic_shield,
                label = stringResource(R.string.home_threats),
                value = formatCount(securityThreats),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                iconRes = R.drawable.ic_shield_on,
                label = stringResource(R.string.home_block_rate),
                value = String.format(androidx.compose.ui.text.intl.Locale.current.platformLocale, "%.1f%%", blockRate),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                iconRes = R.drawable.ic_history,
                label = stringResource(R.string.home_uptime),
                value = formatUptime(uptimeMs),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                iconRes = R.drawable.ic_crown,
                label = stringResource(R.string.home_filter_rules),
                value = formatCount(filterRuleCount),
                modifier = Modifier.weight(1f),
            )
        }

        if (topBlocked.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            TopBlockedSection(topBlocked = topBlocked)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBlockedSection(
    topBlocked: List<app.pwhs.blockadstv.data.entities.TopBlockedDomain>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield_off),
                    contentDescription = null,
                    tint = DangerRed,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_top_blocked_domains),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                topBlocked.take(3).forEach { item ->
                    TopBlockedItem(
                        item = item,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopBlockedItem(
    item: app.pwhs.blockadstv.data.entities.TopBlockedDomain,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { /* focusable item */ }
            .padding(8.dp),
    ) {
        Column {
            Text(
                text = item.domain,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${item.count} blocked",
                style = MaterialTheme.typography.labelSmall,
                color = DangerRed,
            )
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
            .size(96.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
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
            painter = painterResource(R.drawable.ic_power),
            contentDescription = if (vpnEnabled) "Turn off" else "Turn on",
            modifier = Modifier.size(44.dp),
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
    @DrawableRes iconRes: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (isFocused) NeonGreen else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) MaterialTheme.colorScheme.onSurface else TextSecondary,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
