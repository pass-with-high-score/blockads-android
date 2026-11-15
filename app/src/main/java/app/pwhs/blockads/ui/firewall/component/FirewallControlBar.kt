package app.pwhs.blockads.ui.firewall.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.firewall.FirewallFilterType
import app.pwhs.blockads.ui.firewall.FirewallTab

@Composable
fun FirewallControlBar(
    selectedTab: FirewallTab,
    onTabSelected: (FirewallTab) -> Unit,
    userAppsCount: Int,
    systemAppsCount: Int,
    selectedFilter: FirewallFilterType,
    onFilterSelected: (FirewallFilterType) -> Unit,
    blockedCount: Int,
    allowedCount: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Scrollable Material 3 SecondaryScrollableTabRow so long labels stay on 1 line
        SecondaryScrollableTabRow(
            selectedTabIndex = if (selectedTab == FirewallTab.USER) 0 else 1,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            edgePadding = 16.dp,
            indicator = {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(if (selectedTab == FirewallTab.USER) 0 else 1),
                    color = MaterialTheme.colorScheme.primary,
                    height = 2.5.dp
                )
            },
            divider = {}
        ) {
            Tab(
                selected = selectedTab == FirewallTab.USER,
                onClick = { onTabSelected(FirewallTab.USER) },
                text = {
                    Text(
                        text = "${stringResource(R.string.whitelist_tab_user)} ($userAppsCount)",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        softWrap = false,
                        fontWeight = if (selectedTab == FirewallTab.USER) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedTab == FirewallTab.USER) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            )
            Tab(
                selected = selectedTab == FirewallTab.SYSTEM,
                onClick = { onTabSelected(FirewallTab.SYSTEM) },
                text = {
                    Text(
                        text = "${stringResource(R.string.whitelist_tab_system)} ($systemAppsCount)",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        softWrap = false,
                        fontWeight = if (selectedTab == FirewallTab.SYSTEM) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedTab == FirewallTab.SYSTEM) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            )
        }

        // Crisp Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactFilterChip(
                label = stringResource(R.string.firewall_filter_all),
                isSelected = selectedFilter == FirewallFilterType.ALL,
                onClick = { onFilterSelected(FirewallFilterType.ALL) }
            )
            CompactFilterChip(
                label = "${stringResource(R.string.firewall_filter_blocked)} ($blockedCount)",
                isSelected = selectedFilter == FirewallFilterType.BLOCKED,
                onClick = { onFilterSelected(FirewallFilterType.BLOCKED) }
            )
            CompactFilterChip(
                label = "${stringResource(R.string.firewall_filter_allowed)} ($allowedCount)",
                isSelected = selectedFilter == FirewallFilterType.ALLOWED,
                onClick = { onFilterSelected(FirewallFilterType.ALLOWED) }
            )
        }
    }
}

@Composable
private fun CompactFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = isSelected,
            borderColor = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.height(32.dp)
    )
}
