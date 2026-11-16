package app.pwhs.blockads.ui.proxyvpn

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.ProxyProfile
import app.pwhs.blockads.ui.proxyvpn.component.AddProxyNodeDialog
import app.pwhs.blockads.ui.proxyvpn.component.CountryFlagRegistry
import app.pwhs.blockads.ui.proxyvpn.component.UnifiedProxyNodesContent
import app.pwhs.blockads.ui.proxyvpn.component.ProxyHeroCard
import app.pwhs.blockads.utils.NodePingResult
import app.pwhs.blockads.utils.WireGuardGeoIpHelper
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyVpnScreen(
    canNavigateBack: Boolean = true,
    onNavigateBack: () -> Unit,
    onEditProfile: (String) -> Unit,
    onEditProxyProfile: (String) -> Unit = {},
    onAddCustomProxy: () -> Unit = {},
    onNavigateToVpnSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProxyVpnViewModel = koinViewModel()
) {
    val isProxyActive by viewModel.isProxyActive.collectAsStateWithLifecycle()
    val activeProxyType by viewModel.activeProxyType.collectAsStateWithLifecycle()

    val wgProfiles by viewModel.wgProfiles.collectAsStateWithLifecycle()
    val activeWgId by viewModel.activeWgProfileId.collectAsStateWithLifecycle()
    val isLoadingWg by viewModel.isLoadingWg.collectAsStateWithLifecycle()
    val wgPingResults by viewModel.wgPingResults.collectAsStateWithLifecycle()
    val isPingingAllWg by viewModel.isPingingAllWg.collectAsStateWithLifecycle()

    val proxyProfiles by viewModel.proxyProfiles.collectAsStateWithLifecycle()
    val activeProxyId by viewModel.activeProxyProfileId.collectAsStateWithLifecycle()
    val pingResults by viewModel.pingResults.collectAsStateWithLifecycle()
    val isPingingAll by viewModel.isPingingAll.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val networkName by viewModel.networkName.collectAsStateWithLifecycle()
    val networkType by viewModel.networkType.collectAsStateWithLifecycle()
    val currentIp by viewModel.currentIp.collectAsStateWithLifecycle()
    val isRefreshingIp by viewModel.isRefreshingIp.collectAsStateWithLifecycle()
    val lastSubUrl by viewModel.lastSubscriptionUrl.collectAsStateWithLifecycle()
    val isUpdatingSub by viewModel.isUpdatingSubscription.collectAsStateWithLifecycle()
    val proxySubscriptions by viewModel.proxySubscriptions.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val resources = LocalResources.current
    var showAddChoiceSheet by remember { mutableStateOf(false) }
    var showAddProxyDialog by remember { mutableStateOf(false) }
    var showSubscriptionsSheet by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var supportError by remember { mutableStateOf<app.pwhs.blockads.ui.common.SupportErrorInfo?>(null) }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    val renamedMsg = stringResource(R.string.wireguard_renamed)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProxyVpnUiEvent.ProfileImported ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.wireguard_imported, event.name))
                is ProxyVpnUiEvent.ProfileDeleted ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.wireguard_deleted, event.name))
                is ProxyVpnUiEvent.ProfileActivated ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.wireguard_active, event.name))
                is ProxyVpnUiEvent.ProfileRenamed ->
                    snackbarHostState.showSnackbar(renamedMsg)
                is ProxyVpnUiEvent.ProxyVpnToggled ->
                    snackbarHostState.showSnackbar(
                        if (event.enabled) resources.getString(R.string.wireguard_enabled_restarting)
                        else resources.getString(R.string.wireguard_disabled_restarting)
                    )
                is ProxyVpnUiEvent.HttpsFilteringDisabled ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.wireguard_https_filtering_disabled))
                is ProxyVpnUiEvent.DuplicatesRemoved ->
                    snackbarHostState.showSnackbar(
                        if (event.count > 0) "Removed ${event.count} duplicate node(s)"
                        else "No duplicate nodes found"
                    )
                is ProxyVpnUiEvent.SubscriptionUpdated ->
                    snackbarHostState.showSnackbar("Subscription updated ($event.count node(s))")
            }
        }
    }

    val supportNeedHelpLabel = stringResource(R.string.support_need_help)
    val supportSubTitle = stringResource(R.string.support_error_subscription_title)
    LaunchedEffect(error) {
        error?.let { errText ->
            val result = snackbarHostState.showSnackbar(
                message = errText,
                actionLabel = supportNeedHelpLabel,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                supportError = app.pwhs.blockads.ui.common.SupportErrorInfo(
                    title = supportSubTitle,
                    message = errText,
                    details = "Error: $errText",
                    errorType = "PROXY_SUBSCRIPTION"
                )
            }
            viewModel.clearError()
        }
    }

    val activeWg = remember(wgProfiles, activeWgId) {
        wgProfiles.firstOrNull { it.id == activeWgId } ?: wgProfiles.firstOrNull()
    }
    val activeProxy = remember(proxyProfiles, activeProxyId) {
        proxyProfiles.firstOrNull { it.id == activeProxyId } ?: proxyProfiles.firstOrNull()
    }

    val isWgActive = activeProxyType == AppPreferences.PROXY_TYPE_WIREGUARD
    val hasActiveNode = if (isWgActive) activeWg != null else activeProxy != null
    val isCardConnected = isProxyActive && hasActiveNode

    val heroServerName = if (isWgActive && activeWg != null) {
        activeWg.name
    } else {
        activeProxy?.displayName ?: activeWg?.name.orEmpty()
    }

    val heroCountryCode: String? = remember(isWgActive, activeWg, activeProxy) {
        if (isWgActive && activeWg != null) {
            WireGuardGeoIpHelper.getCountryCodeFast(activeWg.name, activeWg.name)
        } else {
            activeProxy?.let { WireGuardGeoIpHelper.getCountryCodeFast(it.server, it.tag) }
                ?: activeWg?.let { WireGuardGeoIpHelper.getCountryCodeFast(it.name, it.name) }
        }
    }

    val heroProtocolText = if (isWgActive && activeWg != null) {
        "WireGuard"
    } else {
        activeProxy?.protocolBadgeText ?: if (activeWg != null) "WireGuard" else ""
    }

    val heroPort = if (isWgActive && activeWg != null) {
        activeWg.config.peers.firstOrNull()?.endpoint?.substringAfterLast(":", "")?.toIntOrNull()
    } else {
        activeProxy?.port ?: activeWg?.config?.peers?.firstOrNull()?.endpoint?.substringAfterLast(":", "")?.toIntOrNull()
    }

    val heroLatency = if (isWgActive && activeWg != null) {
        (wgPingResults[activeWg.id] as? NodePingResult.Success)?.latencyMs
    } else {
        (activeProxy?.let { pingResults[it.id] } as? NodePingResult.Success)?.latencyMs
            ?: (activeWg?.let { wgPingResults[it.id] } as? NodePingResult.Success)?.latencyMs
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (isSearchActive) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.proxy_search_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.accessibility_navigate_back)
                            )
                        }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = null
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.proxy_vpn_title)) },
                    navigationIcon = {
                        if (canNavigateBack) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.accessibility_navigate_back)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.proxy_search_hint)
                            )
                        }
                        IconButton(onClick = onNavigateToVpnSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.vpn_settings_title)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val heroHost = if (isWgActive && activeWg != null) {
            activeWg.config.peers.firstOrNull()?.endpoint?.substringBeforeLast(":")
        } else {
            activeProxy?.server ?: activeWg?.config?.peers?.firstOrNull()?.endpoint?.substringBeforeLast(":")
        }

        val heroCardContent: @Composable () -> Unit = {
            ProxyHeroCard(
                isConnected = isCardConnected,
                onToggleConnection = { viewModel.toggleProxyVpn() },
                serverName = heroServerName,
                countryCode = heroCountryCode,
                protocol = heroProtocolText,
                port = heroPort,
                serverHost = heroHost,
                latencyMs = heroLatency,
                networkName = networkName,
                networkType = networkType,
                currentIp = currentIp,
                isRefreshingIp = isRefreshingIp,
                onRefreshIp = { viewModel.refreshNetworkAndIp() }
            )
        }

        val nodesContent: @Composable (Modifier) -> Unit = { nodesModifier ->
            UnifiedProxyNodesContent(
                wgProfiles = wgProfiles,
                proxyProfiles = proxyProfiles,
                activeWgId = activeWgId,
                activeProxyId = activeProxyId,
                activeProxyType = activeProxyType,
                isProxyActive = isProxyActive,
                isLoadingWg = isLoadingWg,
                wgPingResults = wgPingResults,
                proxyPingResults = pingResults,
                isPingingAll = isPingingAll || isPingingAllWg,
                searchQuery = searchQuery,
                onPingWireGuard = { viewModel.pingWireGuardProfile(it.id) },
                onPingProxy = { viewModel.pingProxyNode(it) },
                onPingAll = { viewModel.pingAll() },
                onSelectWireGuard = { viewModel.setActiveWireGuardProfile(it.id) },
                onSelectProxy = { viewModel.setActiveProxyProfile(it.id) },
                onEditWireGuard = onEditProfile,
                onEditProxy = { onEditProxyProfile(it.id) },
                onRenameWireGuard = { id, name -> viewModel.renameWireGuardProfile(id, name) },
                onRenameProxy = { id, name -> viewModel.renameProxyProfile(id, name) },
                onDeleteWireGuard = { viewModel.deleteWireGuardProfile(it) },
                onDeleteProxy = { viewModel.deleteProxyProfile(it) },
                onImportUri = { viewModel.importWireGuardFromUri(it) },
                onAddClick = { showAddChoiceSheet = true },
                onClearSearch = { searchQuery = "" },
                hasSubscription = proxySubscriptions.isNotEmpty() || !lastSubUrl.isNullOrBlank(),
                isUpdatingSubscription = isUpdatingSub,
                onUpdateSubscription = { viewModel.updateSubscription() },
                onOpenSubscriptions = { showSubscriptionsSheet = true },
                onRemoveDuplicates = { viewModel.removeDuplicates() },
                modifier = nodesModifier
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (rememberIsExpandedLayout(maxWidth)) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        heroCardContent()
                    }

                    nodesContent(
                        Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    heroCardContent()
                    Spacer(modifier = Modifier.height(4.dp))
                    nodesContent(Modifier.weight(1f))
                }
            }
        }
    }

    if (showAddChoiceSheet) {
        app.pwhs.blockads.ui.proxyvpn.component.AddProxyChoiceBottomSheet(
            onDismiss = { showAddChoiceSheet = false },
            onImportLink = { showAddProxyDialog = true },
            onAddCustom = onAddCustomProxy,
            onOpenSubscriptions = { showSubscriptionsSheet = true }
        )
    }

    if (showAddProxyDialog) {
        AddProxyNodeDialog(
            onDismiss = { showAddProxyDialog = false },
            onSave = { uri ->
                viewModel.saveProxyNodeLink(uri)
            }
        )
    }

    if (showSubscriptionsSheet) {
        app.pwhs.blockads.ui.proxyvpn.component.ManageSubscriptionsBottomSheet(
            subscriptions = proxySubscriptions,
            isUpdating = isUpdatingSub,
            onDismiss = { showSubscriptionsSheet = false },
            onUpdateSubscription = { viewModel.updateSubscription(it) },
            onUpdateAll = { viewModel.updateAllSubscriptions() },
            onDeleteSubscription = { id, deleteNodes -> viewModel.deleteSubscription(id, deleteNodes) },
            onAddSubscription = { url ->
                coroutineScope.launch {
                    viewModel.saveProxyNodeLink(url)
                }
            },
            onRemoveDuplicates = { viewModel.removeDuplicates() }
        )
    }

    supportError?.let { errInfo ->
        app.pwhs.blockads.ui.component.ErrorSupportBottomSheet(
            errorInfo = errInfo,
            onDismiss = { supportError = null }
        )
    }
}
