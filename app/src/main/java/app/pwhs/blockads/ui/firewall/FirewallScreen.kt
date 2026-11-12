package app.pwhs.blockads.ui.firewall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.AppListSkeleton
import app.pwhs.blockads.ui.firewall.component.FirewallAppItem
import app.pwhs.blockads.ui.firewall.component.FirewallConfigSheet
import app.pwhs.blockads.ui.firewall.component.FirewallControlBar
import app.pwhs.blockads.ui.firewall.component.FirewallPromptToggle
import app.pwhs.blockads.ui.firewall.component.FirewallSearchBar
import app.pwhs.blockads.ui.firewall.component.FirewallTopBar
import app.pwhs.blockads.ui.theme.TextSecondary
import org.koin.androidx.compose.koinViewModel

@Composable
fun FirewallScreen(
    modifier: Modifier = Modifier,
    viewModel: FirewallViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val allUserEnabled = uiState.userApps.isNotEmpty() &&
        uiState.userApps.all { uiState.isBlocked(it.packageName) }
    val allSystemEnabled = uiState.systemApps.isNotEmpty() &&
        uiState.systemApps.all { uiState.isBlocked(it.packageName) }

    val currentApps = uiState.currentApps
    val blockedCount = currentApps.count { uiState.isBlocked(it.packageName) }
    val allowedCount = currentApps.size - blockedCount

    // Rule configuration bottom sheet
    uiState.configuringApp?.let { app ->
        val existingRule = uiState.rulesMap[app.packageName]
        FirewallConfigSheet(
            app = app,
            existingRule = existingRule,
            onSave = { rule -> viewModel.processIntent(FirewallUiIntent.SaveRule(rule)) },
            onDelete = { viewModel.processIntent(FirewallUiIntent.DeleteRule(app.packageName)) },
            onDismiss = { viewModel.processIntent(FirewallUiIntent.CloseAppConfig) }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            FirewallTopBar(
                isFirewallEnabled = uiState.isFirewallEnabled,
                enabledCount = uiState.enabledCount,
                allUserEnabled = allUserEnabled,
                allSystemEnabled = allSystemEnabled,
                onToggleFirewall = { viewModel.processIntent(FirewallUiIntent.SetFirewallEnabled(it)) },
                onRefresh = { viewModel.processIntent(FirewallUiIntent.RefreshApps) },
                onToggleAllUserApps = { viewModel.processIntent(FirewallUiIntent.ToggleAllUserApps) },
                onToggleAllSystemApps = { viewModel.processIntent(FirewallUiIntent.ToggleAllSystemApps) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Ultra-compact Search Bar
            FirewallSearchBar(
                query = uiState.searchQuery,
                onQueryChange = { viewModel.processIntent(FirewallUiIntent.UpdateSearchQuery(it)) }
            )

            FirewallPromptToggle(
                enabled = uiState.isPromptEnabled,
                onToggle = { viewModel.processIntent(FirewallUiIntent.SetPromptEnabled(it)) }
            )

            // Compact Segmented Tabs + Filter Chips
            FirewallControlBar(
                selectedTab = uiState.selectedTab,
                onTabSelected = { viewModel.processIntent(FirewallUiIntent.SelectTab(it)) },
                userAppsCount = uiState.userApps.size,
                systemAppsCount = uiState.systemApps.size,
                selectedFilter = uiState.filterType,
                onFilterSelected = { viewModel.processIntent(FirewallUiIntent.SelectFilterType(it)) },
                blockedCount = blockedCount,
                allowedCount = allowedCount
            )

            Spacer(modifier = Modifier.size(6.dp))

            if (uiState.isLoading) {
                AppListSkeleton()
            } else {
                val filteredApps = uiState.filteredApps

                if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.whitelist_apps_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val rule = uiState.rulesMap[app.packageName]
                            FirewallAppItem(
                                app = app,
                                rule = rule,
                                onToggle = {
                                    viewModel.processIntent(FirewallUiIntent.ToggleAppBlock(app.packageName))
                                },
                                onConfigure = {
                                    viewModel.processIntent(FirewallUiIntent.OpenAppConfig(app))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
