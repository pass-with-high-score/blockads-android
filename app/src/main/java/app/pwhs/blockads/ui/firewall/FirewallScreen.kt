package app.pwhs.blockads.ui.firewall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.firewall.component.FirewallAppItem
import app.pwhs.blockads.ui.firewall.component.FirewallRuleDialog
import app.pwhs.blockads.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(
    modifier: Modifier = Modifier,
    viewModel: FirewallViewModel = koinViewModel()
) {
    val firewallEnabled by viewModel.firewallEnabled.collectAsStateWithLifecycle()
    val firewallRules by viewModel.firewallRules.collectAsStateWithLifecycle()
    val enabledCount by viewModel.enabledCount.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var configurePackage by remember { mutableStateOf<String?>(null) }
    // 0 = All, 1 = Enabled (blocked), 2 = Disabled
    var filterOption by remember { mutableIntStateOf(0) }
    var showMenuDropdown by remember { mutableStateOf(false) }

    val rulesMap = remember(firewallRules) {
        firewallRules.associateBy { it.packageName }
    }

    val userApps = remember(installedApps) {
        installedApps.filter { !it.isSystemApp }
    }
    val systemApps = remember(installedApps) {
        installedApps.filter { it.isSystemApp }
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    val tabs = listOf(
        stringResource(R.string.whitelist_tab_user),
        stringResource(R.string.whitelist_tab_system)
    )


    // Rule configuration dialog
    configurePackage?.let { pkg ->
        val app = installedApps.find { it.packageName == pkg }
        val existingRule = rulesMap[pkg]
        if (app != null) {
            FirewallRuleDialog(
                appName = app.label,
                existingRule = existingRule,
                packageName = pkg,
                onSave = { rule ->
                    viewModel.saveRule(rule)
                    configurePackage = null
                },
                onDelete = {
                    viewModel.deleteRule(pkg)
                    configurePackage = null
                },
                onDismiss = { configurePackage = null }
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.firewall_title),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.firewall_count, enabledCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = { viewModel.refreshApps() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.app_management_refresh)
                        )
                    }
                    IconButton(onClick = { showMenuDropdown = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        containerColor = MaterialTheme.colorScheme.background,
                        expanded = showMenuDropdown,
                        onDismissRequest = { showMenuDropdown = false }
                    ) {
                        val allUserEnabled =
                            userApps.isNotEmpty() && userApps.all { it.packageName in rulesMap }
                        val allSystemEnabled =
                            systemApps.isNotEmpty() && systemApps.all { it.packageName in rulesMap }

                        // User app — toggle all
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (allUserEnabled) "Disable All User Apps"
                                    else "Enable All User Apps"
                                )
                            },
                            onClick = {
                                showMenuDropdown = false
                                if (allUserEnabled) viewModel.disableAllUserApps()
                                else viewModel.enableAllUserApps()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null
                                )
                            }
                        )
                        // System app — toggle all
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (allSystemEnabled) "Disable All System Apps"
                                    else "Enable All System Apps"
                                )
                            },
                            onClick = {
                                showMenuDropdown = false
                                if (allSystemEnabled) viewModel.disableAllSystemApps()
                                else viewModel.enableAllSystemApps()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Android,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Firewall enable/disable card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (firewallEnabled)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.firewall_enable),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.firewall_enable_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = firewallEnabled,
                        onCheckedChange = { viewModel.setFirewallEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        stringResource(R.string.firewall_search),
                        color = TextSecondary
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterOption == 0,
                    onClick = { filterOption = 0 },
                    label = { Text(stringResource(R.string.filter_chip_all)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                FilterChip(
                    selected = filterOption == 1,
                    onClick = { filterOption = 1 },
                    label = { Text(stringResource(R.string.filter_chip_enabled)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                FilterChip(
                    selected = filterOption == 2,
                    onClick = { filterOption = 2 },
                    label = { Text(stringResource(R.string.filter_chip_disabled)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            // Tabs
            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                tabs.forEachIndexed { index, title ->
                    val count = when (index) {
                        0 -> userApps.size
                        else -> systemApps.size
                    }
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = {
                            Text(
                                "$title ($count)",
                                fontWeight = if (pagerState.currentPage == index)
                                    FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.app_management_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                HorizontalPager(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 12.dp),
                    state = pagerState,
                ) { page ->
                    val appsForPage = when (page) {
                        0 -> userApps
                        else -> systemApps
                    }
                    val filteredApps = remember(searchQuery, appsForPage, rulesMap, filterOption) {
                        appsForPage.filter { app ->
                            val matchesSearch = searchQuery.isBlank() ||
                                    app.label.contains(searchQuery, ignoreCase = true) ||
                                    app.packageName.contains(searchQuery, ignoreCase = true)
                            val matchesFilter = when (filterOption) {
                                1 -> app.packageName in rulesMap
                                2 -> app.packageName !in rulesMap
                                else -> true
                            }
                            matchesSearch && matchesFilter
                        }
                    }

                    if (filteredApps.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.whitelist_apps_empty),
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
                                val rule = rulesMap[app.packageName]
                                FirewallAppItem(
                                    app = app,
                                    rule = rule,
                                    onToggle = { viewModel.toggleAppFirewall(app.packageName) },
                                    onConfigure = { configurePackage = app.packageName }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
