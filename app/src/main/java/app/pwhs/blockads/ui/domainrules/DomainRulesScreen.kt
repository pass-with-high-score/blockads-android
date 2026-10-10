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
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
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
import app.pwhs.blockads.ui.domainrules.dialog.AddFilterRuleDialog
import app.pwhs.blockads.ui.domainrules.dialog.EditFilterRuleDialog
import app.pwhs.blockads.ui.domainrules.dialog.ImportDomainsBottomSheet
import app.pwhs.blockads.ui.domainrules.dialog.RuleTypesDocSheet
import app.pwhs.blockads.ui.event.UiEventEffect
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.utils.ParsedFilterRule
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainRulesScreen(
    modifier: Modifier = Modifier,
    viewModel: DomainRulesViewModel = koinViewModel()
) {
    val activeConfig by viewModel.activeConfig.collectAsStateWithLifecycle()
    val allConfigs by viewModel.allConfigs.collectAsStateWithLifecycle()
    val profileFilterRules by viewModel.profileFilterRules.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showRuleDocSheet by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<ParsedFilterRule?>(null) }

    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()

    val displayableRules = remember(profileFilterRules) {
        profileFilterRules.filter { it.type != "FINAL" }
    }

    val whitelistRules = remember(displayableRules, searchQuery) {
        displayableRules.filter {
            it.policy.equals("DIRECT", ignoreCase = true) &&
                (searchQuery.isBlank() || it.param.contains(searchQuery, ignoreCase = true) || it.type.contains(searchQuery, ignoreCase = true))
        }
    }

    val blocklistRules = remember(displayableRules, searchQuery) {
        displayableRules.filter {
            it.policy.startsWith("REJECT", ignoreCase = true) &&
                (searchQuery.isBlank() || it.param.contains(searchQuery, ignoreCase = true) || it.type.contains(searchQuery, ignoreCase = true))
        }
    }

    UiEventEffect(viewModel.events)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.domain_rules_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        activeConfig?.let { cfg ->
                            Text(
                                text = "${cfg.name} • ${displayableRules.size} profile rules",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showRuleDocSheet = true }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.rule_docs_action_tooltip)
                        )
                    }
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
                        rules = whitelistRules,
                        onToggle = { viewModel.toggleProfileRule(it) },
                        onRemove = { viewModel.removeProfileRule(it) },
                        onEdit = { editingRule = it }
                    )
                    1 -> BlocklistTab(
                        rules = blocklistRules,
                        onToggle = { viewModel.toggleProfileRule(it) },
                        onRemove = { viewModel.removeProfileRule(it) },
                        onEdit = { editingRule = it }
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

    editingRule?.let { rule ->
        EditFilterRuleDialog(
            initialDomain = rule.param,
            initialType = rule.type,
            initialPolicy = rule.policy,
            activeConfig = activeConfig,
            allConfigs = allConfigs,
            onDismiss = { editingRule = null },
            onSave = { type, param, policy, configId ->
                viewModel.updateProfileRule(
                    oldRawLine = rule.rawLine,
                    oldDomain = rule.param,
                    type = type,
                    param = param,
                    policy = policy,
                    targetConfigId = configId
                )
                editingRule = null
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

    if (showRuleDocSheet) {
        RuleTypesDocSheet(
            onDismiss = { showRuleDocSheet = false }
        )
    }
}