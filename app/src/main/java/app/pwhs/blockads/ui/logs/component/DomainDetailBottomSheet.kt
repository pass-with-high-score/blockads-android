package app.pwhs.blockads.ui.logs.component

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.ui.theme.WhitelistAmber
import app.pwhs.blockads.util.formatTimestamp
import com.google.accompanist.drawablepainter.rememberDrawablePainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainDetailBottomSheet(
    entry: DnsLogEntry,
    isWhitelisted: Boolean,
    onDismiss: () -> Unit,
    onCopyDomain: () -> Unit,
    onAddToWhiteList: () -> Unit,
    onAddToCustomBlockRules: () -> Unit,
    onAddWildcardWhitelist: () -> Unit,
    viewModel: app.pwhs.blockads.ui.logs.LogViewModel,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        isWhitelisted -> WhitelistAmber
        entry.isBlocked -> DangerRed
        else -> MaterialTheme.colorScheme.primary
    }
    val statusText = when {
        isWhitelisted -> stringResource(R.string.log_status_whitelisted)
        entry.isBlocked -> stringResource(R.string.log_status_blocked)
        else -> stringResource(R.string.log_status_allowed)
    }

    // State for fetching specific blocking lists
    val blockingLists = remember { androidx.compose.runtime.mutableStateOf<List<String>?>(null) }
    
    androidx.compose.runtime.LaunchedEffect(entry) {
        if (entry.isBlocked && entry.blockedBy.equals(FilterListRepository.BLOCK_REASON_FILTER_LIST, ignoreCase = true)) {
            viewModel.getBlockingFilterLists(entry.domain) { lists ->
                blockingLists.value = lists
            }
        }
    }

    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            // Header with optional app icon
            val context = LocalContext.current
            val appIcon: Drawable? = remember(entry.packageName) {
                if (entry.packageName.isNotEmpty() && entry.packageName.contains(".")) {
                    try {
                        context.packageManager.getApplicationIcon(entry.packageName)
                    } catch (e: Exception) { null }
                } else null
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (appIcon != null) {
                    Image(
                        painter = rememberDrawablePainter(drawable = appIcon),
                        contentDescription = entry.appName,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                Column {
                    Text(
                        text = entry.domain,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Detail info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailRow(
                        label = stringResource(R.string.log_detail_query_type),
                        value = entry.queryType
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DetailRow(
                        label = stringResource(R.string.log_detail_response_time),
                        value = if (entry.responseTimeMs > 0) "${entry.responseTimeMs}ms"
                        else stringResource(R.string.log_detail_na)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DetailRow(
                        label = stringResource(R.string.log_detail_timestamp),
                        value = formatTimestamp(entry.timestamp)
                    )
                    if (entry.appName.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DetailRow(
                            label = stringResource(R.string.log_detail_app),
                            value = entry.appName
                        )
                    }
                    if (entry.resolvedIp.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DetailRow(
                            label = stringResource(R.string.log_detail_resolved_ip),
                            value = entry.resolvedIp
                        )
                    }
                    if (entry.blockedBy.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val blockedByText = when (entry.blockedBy.uppercase()) {
                            FilterListRepository.BLOCK_REASON_CUSTOM_RULE ->
                                stringResource(R.string.block_reason_custom_rule)
                            FilterListRepository.BLOCK_REASON_FILTER_LIST -> {
                                val baseText = stringResource(R.string.block_reason_filter_list)
                                val lists = blockingLists.value
                                if (lists == null) {
                                    "$baseText (Loading...)"
                                } else if (lists.isEmpty()) {
                                    baseText
                                } else {
                                    "$baseText (${lists.joinToString(", ")})"
                                }
                            }
                            FilterListRepository.BLOCK_REASON_SECURITY ->
                                stringResource(R.string.block_reason_security)
                            FilterListRepository.BLOCK_REASON_FIREWALL ->
                                stringResource(R.string.block_reason_firewall)
                            FilterListRepository.BLOCK_REASON_UPSTREAM_DNS.uppercase() ->
                                stringResource(R.string.block_reason_upstream_dns)
                            else -> entry.blockedBy
                        }
                        DetailRow(
                            label = stringResource(R.string.log_detail_blocked_by),
                            value = blockedByText
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions
            if (!isWhitelisted && !entry.isBlocked) {
                // Block action showing only when allowed and not whitelisted
                Card(
                    onClick = onAddToCustomBlockRules,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.block_this_domain),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Whitelist action (only if not already whitelisted)
            if (!isWhitelisted) {
                Card(
                    onClick = onAddToWhiteList,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = WhitelistAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.log_action_whitelist),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                
                // Wildcard Whitelist action
                Card(
                    onClick = onAddWildcardWhitelist,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = WhitelistAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.log_wildcard_whitelist_domain),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Copy domain
            Card(
                onClick = onCopyDomain,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.log_action_copy),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
