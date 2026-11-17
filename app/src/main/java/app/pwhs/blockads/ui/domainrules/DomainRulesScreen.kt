package app.pwhs.blockads.ui.domainrules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.LocalFloatingBottomBarPadding
import app.pwhs.blockads.data.model.SniProxyRule
import app.pwhs.blockads.ui.component.UndoSnackbarHost
import app.pwhs.blockads.ui.domainrules.component.BlocklistTab
import app.pwhs.blockads.ui.domainrules.component.SniProxyTab
import app.pwhs.blockads.ui.domainrules.component.WhitelistTab
import app.pwhs.blockads.ui.domainrules.dialog.AddEditSniProxyRuleDialog
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
    val profileFilterRules by viewModel.profileFilterRules.collectAsStateWithLifecycle()
    val sniProxyRules by viewModel.sniProxyRules.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSniProxyDialog by remember { mutableStateOf(false) }
    var sniProxyPresetYouTube by remember { mutableStateOf(false) }
    var editingSniProxyRule by remember { mutableStateOf<SniProxyRule?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showRuleDocSheet by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<ParsedFilterRule?>(null) }

    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources

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

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 2) {
            viewModel.loadInstalledAppsIfNeeded()
        }
    }

    UiEventEffect(viewModel.events)

    LaunchedEffect(viewModel) {
        viewModel.undoRemovedRule.collect { rule ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val message = if (rule.policy.equals("DIRECT", ignoreCase = true)) {
                resources.getString(R.string.whitelist_domain_removed_named, rule.param)
            } else {
                resources.getString(R.string.blocklist_domain_removed_named, rule.param)
            }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = resources.getString(R.string.action_undo),
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreProfileRule(rule)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { UndoSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.domain_rules_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        val totalRules = displayableRules.size + sniProxyRules.size
                        Text(
                            text = pluralStringResource(R.plurals.domain_rules_count, totalRules, totalRules),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                        onClick = {
                            if (pagerState.currentPage == 2) {
                                viewModel.loadInstalledAppsIfNeeded()
                                editingSniProxyRule = null
                                sniProxyPresetYouTube = false
                                showSniProxyDialog = true
                            } else {
                                showAddDialog = true
                            }
                        }
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
                modifier = Modifier.padding(bottom = LocalFloatingBottomBarPadding.current),
                onClick = {
                    if (pagerState.currentPage == 2) {
                        viewModel.loadInstalledAppsIfNeeded()
                        editingSniProxyRule = null
                        sniProxyPresetYouTube = false
                        showSniProxyDialog = true
                    } else {
                        showAddDialog = true
                    }
                },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
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
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
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
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
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
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = {
                        Text(
                            stringResource(R.string.domain_rules_tab_sni_proxy),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (pagerState.currentPage == 2) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.AltRoute,
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

            val appLabelMap = remember(installedApps) {
                installedApps.associate { it.packageName to it.label }
            }
            LaunchedEffect(profileFilterRules) {
                if (profileFilterRules.any { it.packageName.isNotEmpty() }) {
                    viewModel.loadInstalledAppsIfNeeded()
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> WhitelistTab(
                        rules = whitelistRules,
                        appLabelMap = appLabelMap,
                        onToggle = { viewModel.toggleProfileRule(it) },
                        onRemove = { viewModel.removeProfileRule(it) },
                        onEdit = {
                            viewModel.loadInstalledAppsIfNeeded()
                            editingRule = it
                        }
                    )
                    1 -> BlocklistTab(
                        rules = blocklistRules,
                        appLabelMap = appLabelMap,
                        onToggle = { viewModel.toggleProfileRule(it) },
                        onRemove = { viewModel.removeProfileRule(it) },
                        onEdit = {
                            viewModel.loadInstalledAppsIfNeeded()
                            editingRule = it
                        }
                    )
                    2 -> {
                        val filteredSniRules = remember(sniProxyRules, searchQuery) {
                            if (searchQuery.isBlank()) sniProxyRules
                            else sniProxyRules.filter {
                                it.name.contains(searchQuery, ignoreCase = true) ||
                                    it.targetIp.contains(searchQuery, ignoreCase = true) ||
                                    it.domains.any { d -> d.contains(searchQuery, ignoreCase = true) }
                            }
                        }
                        SniProxyTab(
                            rules = filteredSniRules,
                            installedApps = installedApps,
                            onToggle = { viewModel.toggleSniProxyRule(it) },
                            onDelete = { viewModel.deleteSniProxyRule(it) },
                            onEdit = {
                                viewModel.loadInstalledAppsIfNeeded()
                                editingSniProxyRule = it
                                sniProxyPresetYouTube = false
                                showSniProxyDialog = true
                            },
                            onQuickAddYouTube = {
                                viewModel.loadInstalledAppsIfNeeded()
                                editingSniProxyRule = null
                                sniProxyPresetYouTube = true
                                showSniProxyDialog = true
                            }
                        )
                    }
                }
            }
        }
        }
    }

    if (showSniProxyDialog) {
        AddEditSniProxyRuleDialog(
            initialRule = editingSniProxyRule,
            presetYouTube = sniProxyPresetYouTube,
            installedApps = installedApps,
            onDismiss = {
                showSniProxyDialog = false
                editingSniProxyRule = null
            },
            onSave = { rule ->
                viewModel.saveSniProxyRule(rule)
                showSniProxyDialog = false
                editingSniProxyRule = null
            }
        )
    }

    if (showAddDialog) {
        LaunchedEffect(Unit) { viewModel.loadInstalledAppsIfNeeded() }
        AddFilterRuleDialog(
            initialPolicy = if (pagerState.currentPage == 0) "DIRECT" else "REJECT",
            installedApps = installedApps,
            onDismiss = { showAddDialog = false },
            onSave = { type, param, policy, packageName ->
                viewModel.addProfileRule(
                    type = type,
                    param = param,
                    policy = policy,
                    packageName = packageName
                )
                showAddDialog = false
            }
        )
    }

    editingRule?.let { rule ->
        LaunchedEffect(rule) { viewModel.loadInstalledAppsIfNeeded() }
        EditFilterRuleDialog(
            initialDomain = rule.param,
            initialType = rule.type,
            initialPolicy = rule.policy,
            initialPackageName = rule.packageName,
            installedApps = installedApps,
            onDismiss = { editingRule = null },
            onSave = { type, param, policy, packageName ->
                viewModel.updateProfileRule(
                    oldRawLine = rule.rawLine,
                    oldDomain = rule.param,
                    type = type,
                    param = param,
                    policy = policy,
                    packageName = packageName
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