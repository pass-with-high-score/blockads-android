package app.pwhs.blockads.ui.domainrules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.domainrules.component.BlocklistTab
import app.pwhs.blockads.ui.domainrules.component.WhitelistTab
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import app.pwhs.blockads.ui.domainrules.dialog.AddFilterRuleDialog
import app.pwhs.blockads.ui.domainrules.dialog.EditFilterRuleDialog
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material.icons.filled.FileUpload
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.ui.domainrules.dialog.ImportDomainsBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainRulesScreen(
    modifier: Modifier = Modifier,
    viewModel: DomainRulesViewModel = koinViewModel()
) {
    val whitelistDomains by viewModel.whitelistDomains.collectAsStateWithLifecycle()
    val blocklistDomains by viewModel.blocklistDomains.collectAsStateWithLifecycle()
    val activeConfig by viewModel.activeConfig.collectAsStateWithLifecycle()
    val allConfigs by viewModel.allConfigs.collectAsStateWithLifecycle()
    val profileFilterRules by viewModel.profileFilterRules.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var editingWhitelistDomain by remember { mutableStateOf<WhitelistDomain?>(null) }
    var editingBlocklistRule by remember { mutableStateOf<CustomDnsRule?>(null) }

    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()

    val filteredWhitelist = remember(whitelistDomains, searchQuery) {
        if (searchQuery.isBlank()) whitelistDomains
        else whitelistDomains.filter { it.domain.contains(searchQuery, ignoreCase = true) }
    }

    val filteredBlocklist = remember(blocklistDomains, searchQuery) {
        if (searchQuery.isBlank()) blocklistDomains
        else blocklistDomains.filter { it.domain.contains(searchQuery, ignoreCase = true) }
    }

    UiEventEffect(viewModel.events)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.domain_rules_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(
                        onClick = { showImportDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = stringResource(R.string.settings_import)
                        )
                    }
                    IconButton(
                        onClick = { showAddDialog = true }
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.whitelist_domains_add)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            activeConfig?.let { cfg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Profile: ${cfg.name}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${profileFilterRules.size} rules in profile",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Tab row
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = {
                        Text(
                            stringResource(R.string.domain_rules_tab_whitelist),
                            fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = {
                        Text(
                            stringResource(R.string.domain_rules_tab_blocklist),
                            fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        stringResource(R.string.whitelist_domains_hint),
                        color = TextSecondary
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
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

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> WhitelistTab(
                        domains = filteredWhitelist,
                        onToggle = { viewModel.toggleWhitelistDomain(it) },
                        onRemove = { viewModel.removeWhitelistDomain(it) },
                        onEdit = { editingWhitelistDomain = it }
                    )
                    1 -> BlocklistTab(
                        domains = filteredBlocklist,
                        onToggle = { viewModel.toggleBlocklistDomain(it) },
                        onRemove = { viewModel.removeBlocklistDomain(it) },
                        onEdit = { editingBlocklistRule = it }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddFilterRuleDialog(
            initialPolicy = if (pagerState.currentPage == 0) "DIRECT" else "REJECT",
            activeConfig = activeConfig,
            allConfigs = allConfigs,
            onDismiss = { showAddDialog = false },
            onSave = { type, param, policy, configId ->
                viewModel.addProfileRule(
                    type = type,
                    param = param,
                    policy = policy,
                    targetConfigId = configId
                )
                showAddDialog = false
            }
        )
    }

    editingWhitelistDomain?.let { domain ->
        EditFilterRuleDialog(
            initialDomain = domain.domain,
            initialPolicy = "DIRECT",
            activeConfig = activeConfig,
            allConfigs = allConfigs,
            onDismiss = { editingWhitelistDomain = null },
            onSave = { type, param, policy, configId ->
                viewModel.updateProfileRule(
                    oldDomain = domain.domain,
                    type = type,
                    param = param,
                    policy = policy,
                    targetConfigId = configId
                )
                editingWhitelistDomain = null
            }
        )
    }

    editingBlocklistRule?.let { rule ->
        EditFilterRuleDialog(
            initialDomain = rule.domain,
            initialPolicy = "REJECT",
            activeConfig = activeConfig,
            allConfigs = allConfigs,
            onDismiss = { editingBlocklistRule = null },
            onSave = { type, param, policy, configId ->
                viewModel.updateProfileRule(
                    oldDomain = rule.domain,
                    type = type,
                    param = param,
                    policy = policy,
                    targetConfigId = configId
                )
                editingBlocklistRule = null
            }
        )
    }

    if (showImportDialog) {
        ImportDomainsBottomSheet(
            initialIsAllow = pagerState.currentPage == 0,
            onDismiss = { showImportDialog = false },
            onImport = { domains, isAllow ->
                viewModel.importDomains(domains, isAllow)
                showImportDialog = false
            }
        )
    }
}