package app.pwhs.blockads.ui.tailscale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscalePeerDetailSheet(
    peer: TailscalePeer,
    onDismiss: () -> Unit,
    onTogglePin: (Long) -> Unit,
    onPing: (Long, String) -> Unit,
    onCopy: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Name, OS, Status, Pin Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TailscaleDeviceUtils.getDeviceIcon(peer.os),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (peer.online) Color(0xFF22C55E) else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (peer.online) stringResource(R.string.tailscale_online) else stringResource(R.string.tailscale_offline),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (peer.online) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val osFormatted = TailscaleDeviceUtils.formatDeviceOs(peer.os)
                            if (osFormatted != "Unknown" && osFormatted.isNotBlank()) {
                                Text(
                                    text = " • $osFormatted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (peer.isSelf) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.tailscale_this_device),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { onTogglePin(peer.id) }) {
                    Icon(
                        imageVector = if (peer.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (peer.isPinned) stringResource(R.string.tailscale_unpin_peer) else stringResource(R.string.tailscale_pin_peer),
                        tint = if (peer.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // IP Addresses Section
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val ipv4 = peer.ipv4 ?: peer.ips.firstOrNull { !it.contains(":") }
                    if (!ipv4.isNullOrEmpty()) {
                        DetailRow(
                            label = stringResource(R.string.tailscale_ipv4),
                            value = ipv4,
                            onCopy = { onCopy(ipv4, "IPv4") }
                        )
                    }

                    val ipv6 = peer.ipv6 ?: peer.ips.firstOrNull { it.contains(":") }
                    if (!ipv6.isNullOrEmpty()) {
                        DetailRow(
                            label = stringResource(R.string.tailscale_ipv6),
                            value = ipv6,
                            onCopy = { onCopy(ipv6, "IPv6") }
                        )
                    }

                    if (peer.name.isNotEmpty()) {
                        DetailRow(
                            label = stringResource(R.string.tailscale_magic_dns),
                            value = peer.name,
                            onCopy = { onCopy(peer.name, "MagicDNS") }
                        )
                    }
                }
            }

            // Connection & Route Details
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val osFormatted = TailscaleDeviceUtils.formatDeviceOs(peer.os)
                    if (osFormatted != "Unknown" && osFormatted.isNotBlank()) {
                        DetailRow(
                            label = stringResource(R.string.tailscale_os),
                            value = osFormatted,
                            onCopy = { onCopy(osFormatted, "OS") }
                        )
                    }

                    val routeValue = when {
                        peer.curAddr.isNotEmpty() -> "${stringResource(R.string.tailscale_route_direct)} (${peer.curAddr})"
                        peer.relay.isNotEmpty() -> "${stringResource(R.string.tailscale_route_relay)} (${peer.relay})"
                        else -> stringResource(R.string.tailscale_route_relay)
                    }
                    DetailRow(
                        label = stringResource(R.string.tailscale_route_type),
                        value = routeValue,
                        onCopy = { onCopy(routeValue, "Route") }
                    )

                    val expiryText = if (peer.keyExpiry > 0) {
                        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                        sdf.format(Date(peer.keyExpiry * 1000))
                    } else {
                        stringResource(R.string.tailscale_key_never_expires)
                    }
                    DetailRow(
                        label = stringResource(R.string.tailscale_key_expiry),
                        value = expiryText,
                        isWarning = peer.keyExpired,
                        warningLabel = if (peer.keyExpired) stringResource(R.string.tailscale_key_expired) else null,
                        onCopy = { onCopy(expiryText, "Key Expiry") }
                    )
                }
            }

            // Ping Probe Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tailscale_ping),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        when {
                            peer.isSelf -> {
                                Text(
                                    text = stringResource(R.string.tailscale_ping_self),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            !peer.online -> {
                                Text(
                                    text = stringResource(R.string.tailscale_ping_offline),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            peer.isPingLoading -> {
                                Text(
                                    text = stringResource(R.string.tailscale_pinging),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            peer.lastPingMs != null -> {
                                Text(
                                    text = "${peer.lastPingMs} ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF16A34A)
                                )
                            }
                            peer.pingError != null -> {
                                Text(
                                    text = peer.pingError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            else -> {
                                Text(
                                    text = "-- ms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    val canPing = peer.online && !peer.isSelf
                    OutlinedButton(
                        onClick = {
                            onPing(peer.id, peer.id.toString())
                        },
                        enabled = !peer.isPingLoading && canPing
                    ) {
                        if (peer.isPingLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.tailscale_ping))
                        }
                    }
                }
            }

            // Copy All Button
            Button(
                onClick = {
                    val summary = buildString {
                        appendLine("Device: ${peer.name}")
                        if (peer.os.isNotEmpty()) appendLine("OS: ${peer.os}")
                        peer.ipv4?.let { appendLine("IPv4: $it") }
                        peer.ipv6?.let { appendLine("IPv6: $it") }
                        appendLine("Status: ${if (peer.online) "Online" else "Offline"}")
                        if (peer.curAddr.isNotEmpty()) appendLine("Endpoint: ${peer.curAddr}")
                        if (peer.relay.isNotEmpty()) appendLine("Relay: ${peer.relay}")
                    }
                    onCopy(summary.trim(), peer.name)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.tailscale_copy_all))
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
    warningLabel: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (isWarning && warningLabel != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = warningLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
