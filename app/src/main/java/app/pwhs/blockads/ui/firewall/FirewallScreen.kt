package app.pwhs.blockads.ui.firewall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.LocalFloatingBottomBarPadding
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.component.AppListSkeleton
import app.pwhs.blockads.ui.firewall.component.FirewallAppItem
import app.pwhs.blockads.ui.firewall.component.FirewallConfigContent
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
    val pendingPromptCount by viewModel.pendingPromptCount.collectAsStateWithLifecycle()
    var isSearchVisible by rememberSaveable { mutableStateOf(false) }

    val allUserEnabled = uiState.userApps.isNotEmpty() &&
        uiState.userApps.all { uiState.isBlocked(it.packageName) }
    val allSystemEnabled = uiState.systemApps.isNotEmpty() &&
        uiState.systemApps.all { uiState.isBlocked(it.packageName) }

    val currentApps = uiState.currentApps
    val blockedCount = currentApps.count { uiState.isBlocked(it.packageName) }
    val allowedCount = currentApps.size - blockedCount

    Scaffold(
        modifier = modifier,
        topBar = {
            FirewallTopBar(
                isFirewallEnabled = uiState.isFirewallEnabled,
                enabledCount = uiState.enabledCount,
                allUserEnabled = allUserEnabled,
                allSystemEnabled = allSystemEnabled,
                isSearchVisible = isSearchVisible,
                onToggleSearch = {
                    val next = !isSearchVisible
                    isSearchVisible = next
                    if (!next && uiState.searchQuery.isNotEmpty()) {
                        viewModel.processIntent(FirewallUiIntent.UpdateSearchQuery(""))
                    }
                },
                onToggleFirewall = { viewModel.processIntent(FirewallUiIntent.SetFirewallEnabled(it)) },
                onRefresh = { viewModel.processIntent(FirewallUiIntent.RefreshApps) },
                onToggleAllUserApps = { viewModel.processIntent(FirewallUiIntent.ToggleAllUserApps) },
                onToggleAllSystemApps = { viewModel.processIntent(FirewallUiIntent.ToggleAllSystemApps) }
            )
        }
    ) { innerPadding ->
        val filteredApps = uiState.filteredApps
        val listPaneContent: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(modifier = paneModifier) {
                AnimatedVisibility(visible = isSearchVisible) {
                    FirewallSearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = { viewModel.processIntent(FirewallUiIntent.UpdateSearchQuery(it)) }
                    )
                }

                FirewallPromptToggle(
                    enabled = uiState.isPromptEnabled,
                    onToggle = { viewModel.processIntent(FirewallUiIntent.SetPromptEnabled(it)) }
                )

                AnimatedVisibility(visible = pendingPromptCount > 0) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.firewall_prompt_pending_banner, pendingPromptCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { viewModel.processIntent(FirewallUiIntent.ReopenPrompts) }
                            ) {
                                Text(stringResource(R.string.firewall_prompt_reopen))
                            }
                        }
                    }
                }

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
                } else if (filteredApps.isEmpty()) {
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
                        contentPadding = PaddingValues(bottom = 16.dp + LocalFloatingBottomBarPadding.current),
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

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isListDetail = rememberIsExpandedLayout(maxWidth)

            if (isListDetail) {
                val activeApp = uiState.configuringApp ?: filteredApps.firstOrNull()

                Row(modifier = Modifier.fillMaxSize()) {
                    listPaneContent(
                        Modifier
                            .weight(0.54f)
                            .fillMaxHeight()
                    )

                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    Box(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .padding(top = 12.dp)
                    ) {
                        if (activeApp != null) {
                            val existingRule = uiState.rulesMap[activeApp.packageName]
                            FirewallConfigContent(
                                app = activeApp,
                                existingRule = existingRule,
                                onSave = { rule ->
                                    viewModel.processIntent(
                                        FirewallUiIntent.SaveRule(rule, dismissSheet = false)
                                    )
                                },
                                onDelete = {
                                    viewModel.processIntent(
                                        FirewallUiIntent.DeleteRule(activeApp.packageName, dismissSheet = false)
                                    )
                                }
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.whitelist_apps_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            } else {
                listPaneContent(Modifier.fillMaxSize())

                uiState.configuringApp?.let { app ->
                    val existingRule = uiState.rulesMap[app.packageName]
                    FirewallConfigSheet(
                        app = app,
                        existingRule = existingRule,
                        onSave = { rule ->
                            viewModel.processIntent(
                                FirewallUiIntent.SaveRule(rule, dismissSheet = false)
                            )
                        },
                        onDelete = { viewModel.processIntent(FirewallUiIntent.DeleteRule(app.packageName)) },
                        onDismiss = { viewModel.processIntent(FirewallUiIntent.CloseAppConfig) }
                    )
                }
            }
        }
    }
}
