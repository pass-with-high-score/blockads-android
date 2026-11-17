package app.pwhs.blockads.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.about.AboutScreen
import app.pwhs.blockads.ui.appearance.AppearanceLanguageScreen
import app.pwhs.blockads.ui.appearance.AppearanceNavigationScreen
import app.pwhs.blockads.ui.appearance.AppearanceThemeScreen
import app.pwhs.blockads.ui.appmanagement.AppManagementScreen
import app.pwhs.blockads.ui.data.AboutKey
import app.pwhs.blockads.ui.data.AppManagementKey
import app.pwhs.blockads.ui.data.AppearanceLanguageKey
import app.pwhs.blockads.ui.data.AppearanceNavigationKey
import app.pwhs.blockads.ui.data.AppearanceThemeKey
import app.pwhs.blockads.ui.browser.BrowserActivity
import app.pwhs.blockads.ui.browser.BrowserScreen
import app.pwhs.blockads.ui.browser.elementrules.ElementRulesScreen
import app.pwhs.blockads.ui.data.BottomBarScreen
import app.pwhs.blockads.ui.data.BrowserKey
import app.pwhs.blockads.ui.data.ElementRulesKey
import app.pwhs.blockads.ui.data.DnsProviderKey
import app.pwhs.blockads.ui.data.DomainRulesKey
import app.pwhs.blockads.ui.data.FilterDetailKey
import app.pwhs.blockads.ui.data.FilterOverlapKey
import app.pwhs.blockads.ui.data.FilterKey
import app.pwhs.blockads.ui.data.FireWallKey
import app.pwhs.blockads.ui.data.HomeKey
import app.pwhs.blockads.ui.data.HttpsFilteringKey
import app.pwhs.blockads.ui.data.CertInstallationWizardKey
import app.pwhs.blockads.ui.data.GeoIpKey
import app.pwhs.blockads.ui.httpsfiltering.wizard.CertInstallationWizardScreen
import app.pwhs.blockads.ui.settings.geoip.GeoIpScreen
import app.pwhs.blockads.ui.data.LogsKey
import app.pwhs.blockads.ui.data.SettingsKey
import app.pwhs.blockads.ui.data.StatisticsKey
import app.pwhs.blockads.ui.data.TrustedNetworksKey
import app.pwhs.blockads.ui.data.WireGuardEditKey
import app.pwhs.blockads.ui.data.ProxyVpnKey
import app.pwhs.blockads.ui.data.ProxyEditKey
import app.pwhs.blockads.ui.data.VpnSettingsKey
import app.pwhs.blockads.ui.data.WireGuardImportKey
import app.pwhs.blockads.ui.data.TailscaleKey
import app.pwhs.blockads.ui.tailscale.TailscaleScreen
import app.pwhs.blockads.ui.proxyvpn.edit.ProxyEditScreen
import app.pwhs.blockads.ui.dnsprovider.DnsProviderScreen
import app.pwhs.blockads.ui.domainrules.DomainRulesScreen
import app.pwhs.blockads.ui.filter.FilterSetupScreen
import app.pwhs.blockads.ui.filter.detail.FilterDetailScreen
import app.pwhs.blockads.ui.filter.overlap.FilterOverlapScreen
import app.pwhs.blockads.ui.firewall.FirewallScreen
import app.pwhs.blockads.ui.component.BlockAdsNavigationBar
import app.pwhs.blockads.ui.component.BlockAdsNavigationRail
import app.pwhs.blockads.ui.component.LocalFloatingBottomBarPadding
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.home.HomeScreen
import app.pwhs.blockads.ui.home.component.FeatureBadgeKind
import app.pwhs.blockads.ui.home.component.HomeFeatureTipType
import app.pwhs.blockads.ui.httpsfiltering.HttpsFilteringScreen
import app.pwhs.blockads.ui.logs.LogsScreen
import app.pwhs.blockads.ui.settings.SettingsDetailPane
import app.pwhs.blockads.ui.settings.SettingsScreen
import app.pwhs.blockads.ui.statistics.StatisticsScreen
import app.pwhs.blockads.ui.wireguard.WireGuardEditScreen
import app.pwhs.blockads.ui.proxyvpn.ProxyVpnScreen
import app.pwhs.blockads.ui.update.AppUpdateDialog
import app.pwhs.blockads.ui.update.findActivity
import app.pwhs.blockads.ui.vpnsettings.VpnSettingsScreen
import app.pwhs.blockads.update.AppUpdateController
import app.pwhs.blockads.update.UpdateChannel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun HomeApp(
    onRequestVpnPermission: () -> Unit = {},
    onShowVpnConflictDialog: () -> Unit = {}
) {
    val context = LocalContext.current
    val appPrefs: AppPreferences = koinInject()
    val updateController: AppUpdateController = koinInject()
    val updateState by updateController.state.collectAsStateWithLifecycle()
    val updateScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (updateController.supportsInAppUpdate) {
            val savedChannel = appPrefs.getUpdateChannelSnapshot()
            val channel = if (savedChannel != null) {
                UpdateChannel.fromId(savedChannel)
            } else {
                UpdateChannel.fromVersionName(BuildConfig.VERSION_NAME)
            }
            updateController.checkForUpdate(channel = channel, manual = false)
        }
    }
    val showBottomNavLabels by appPrefs.showBottomNavLabels.collectAsStateWithLifecycle(
        initialValue = true,
    )
    val firewallEnabled by appPrefs.firewallEnabled.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val secondaryTab by appPrefs.bottomNavSecondaryTab.collectAsStateWithLifecycle(
        initialValue = AppPreferences.BOTTOM_NAV_TAB_AUTO,
    )
    val navRailPosition by appPrefs.navRailPosition.collectAsStateWithLifecycle(
        initialValue = AppPreferences.NAV_POSITION_LEFT,
    )
    val floatingCapsuleNav by appPrefs.floatingCapsuleNav.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val homeStack = rememberNavBackStack(HomeKey)
    val filterStack = rememberNavBackStack(FilterKey)
    val wireguardStack = rememberNavBackStack(ProxyVpnKey)
    val firewallStack = rememberNavBackStack(FireWallKey)
    val domainRuleStack = rememberNavBackStack(DomainRulesKey)
    val settingsStack = rememberNavBackStack(SettingsKey)
    var currentTab by rememberSaveable { mutableStateOf(BottomBarScreen.Home) }

    val currentBackStack = when (currentTab) {
        BottomBarScreen.Home -> homeStack
        BottomBarScreen.FilterSetup -> filterStack
        BottomBarScreen.Proxy -> wireguardStack
        BottomBarScreen.Firewall -> firewallStack
        BottomBarScreen.DomainRule -> domainRuleStack
        BottomBarScreen.Settings -> settingsStack
    }

    val bottomBarScreens = remember(secondaryTab) {
        BottomBarScreen.resolveBottomBarScreens(secondaryTab, BuildConfig.IS_STORE)
    }
    var showBottomBar by rememberSaveable { mutableStateOf(true) }
    fun safePop(stack: MutableList<*>) {
        if (stack.size > 1) {
            stack.removeLastOrNull()
        }
        showBottomBar = stack.size <= 1
    }

    var pendingSettingsPane by remember { mutableStateOf<SettingsDetailPane?>(null) }
    var pendingFilterId by remember { mutableStateOf<Long?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = rememberIsExpandedLayout(maxWidth)
        val useNavRail = isWideScreen && navRailPosition != AppPreferences.NAV_POSITION_BOTTOM

        // When unfolding from a single-pane detail screen into two-pane mode,
        // collapse the backstack to the list root and open that item in the detail pane.
        val settingsTopKey = settingsStack.lastOrNull()
        LaunchedEffect(isWideScreen, settingsTopKey) {
            if (isWideScreen && settingsStack.size > 1) {
                val mappedPane = when (settingsTopKey) {
                    is AboutKey -> SettingsDetailPane.ABOUT
                    is AppearanceThemeKey -> SettingsDetailPane.APPEARANCE_THEME
                    is AppearanceNavigationKey -> SettingsDetailPane.APPEARANCE_NAVIGATION
                    is AppearanceLanguageKey -> SettingsDetailPane.APPEARANCE_LANGUAGE
                    is AppManagementKey -> SettingsDetailPane.APP_MANAGEMENT
                    is TrustedNetworksKey -> SettingsDetailPane.TRUSTED_NETWORKS
                    is TailscaleKey -> SettingsDetailPane.TAILSCALE
                    is HttpsFilteringKey -> SettingsDetailPane.HTTPS_FILTERING
                    is DnsProviderKey -> SettingsDetailPane.DNS_PROVIDER
                    is GeoIpKey -> SettingsDetailPane.GEO_IP
                    else -> null
                }
                if (mappedPane != null) {
                    pendingSettingsPane = mappedPane
                    while (settingsStack.size > 1) {
                        settingsStack.removeLastOrNull()
                    }
                    if (currentTab == BottomBarScreen.Settings) {
                        showBottomBar = true
                    }
                }
            }
        }

        val filterTopKey = filterStack.lastOrNull()
        LaunchedEffect(isWideScreen, filterTopKey) {
            if (isWideScreen && filterStack.size > 1 && filterTopKey is FilterDetailKey) {
                pendingFilterId = filterTopKey.filterId
                while (filterStack.size > 1) {
                    filterStack.removeLastOrNull()
                }
                if (currentTab == BottomBarScreen.FilterSetup) {
                    showBottomBar = true
                }
            }
        }

        // When on a non-Home tab root, back should switch to Home tab instead of exiting
        BackHandler(enabled = currentTab != BottomBarScreen.Home && currentBackStack.size <= 1) {
            currentTab = BottomBarScreen.Home
            showBottomBar = true
        }

        val renderNavDisplay: @Composable (Modifier) -> Unit = { navModifier ->
            NavDisplay(
                backStack = currentBackStack,
                onBack = {
                    safePop(currentBackStack)
                },
                modifier = navModifier,
            entryProvider = entryProvider {
                entry<HomeKey> {
                    HomeScreen(
                        onShowVpnConflictDialog = onShowVpnConflictDialog,
                        onRequestVpnPermission = onRequestVpnPermission,
                        onNavigateToLogScreen = { filterStatus ->
                            showBottomBar = false
                            homeStack.add(LogsKey(filterStatus))
                        },
                        onNavigateToLogsWithQuery = { domain ->
                            showBottomBar = false
                            homeStack.add(LogsKey(searchQuery = domain))
                        },
                        onNavigateToStatisticsScreen = {
                            showBottomBar = false
                            homeStack.add(StatisticsKey)
                        },
                        onNavigateToBrowser = { url ->
                            context.startActivity(BrowserActivity.createIntent(context, url))
                        },
                        onNavigateToFeature = { featureType ->
                            showBottomBar = false
                            when (featureType) {
                                HomeFeatureTipType.APP_MANAGEMENT -> homeStack.add(AppManagementKey)
                                HomeFeatureTipType.TRUSTED_NETWORKS -> homeStack.add(TrustedNetworksKey)
                                HomeFeatureTipType.HTTPS_FILTERING -> homeStack.add(HttpsFilteringKey)
                                HomeFeatureTipType.WIREGUARD -> homeStack.add(WireGuardImportKey)
                            }
                        },
                        onFeatureBadgeClick = { kind ->
                            when (kind) {
                                FeatureBadgeKind.DNS_PROTOCOL -> {
                                    showBottomBar = false
                                    homeStack.add(DnsProviderKey)
                                }
                                FeatureBadgeKind.HTTPS_FILTER -> {
                                    showBottomBar = false
                                    homeStack.add(HttpsFilteringKey)
                                }
                                FeatureBadgeKind.TAILSCALE -> {
                                    showBottomBar = false
                                    homeStack.add(TailscaleKey)
                                }
                                FeatureBadgeKind.FIREWALL -> {
                                    showBottomBar = true
                                    currentTab = BottomBarScreen.Firewall
                                }
                                FeatureBadgeKind.FILTER_RULES -> {
                                    showBottomBar = true
                                    currentTab = BottomBarScreen.FilterSetup
                                }
                                FeatureBadgeKind.TUNNEL_MODE -> Unit
                            }
                        }
                    )
                }
                entry<FilterKey> {
                    FilterSetupScreen(
                        canNavigateBack = currentBackStack.size > 1,
                        onNavigateBack = {
                            safePop(currentBackStack)
                        },
                        onNavigateToFilterDetail = { filterId ->
                            showBottomBar = false
                            currentBackStack.add(FilterDetailKey(filterId))
                        },
                        onNavigateToOverlap = {
                            showBottomBar = false
                            currentBackStack.add(FilterOverlapKey)
                        },
                        externalFilterId = pendingFilterId,
                        onExternalFilterIdConsumed = { pendingFilterId = null }
                    )
                }
                entry<FireWallKey> {
                    FirewallScreen()
                }
                entry<DomainRulesKey> {
                    DomainRulesScreen()
                }
                entry<SettingsKey> {
                    SettingsScreen(
                        onNavigateToAbout = {
                            showBottomBar = false
                            settingsStack.add(AboutKey)
                        },
                        onNavigateToTheme = {
                            showBottomBar = false
                            settingsStack.add(AppearanceThemeKey)
                        },
                        onNavigateToNavigation = {
                            showBottomBar = false
                            settingsStack.add(AppearanceNavigationKey)
                        },
                        onNavigateToLanguage = {
                            showBottomBar = false
                            settingsStack.add(AppearanceLanguageKey)
                        },
                        onNavigateToAppManagement = {
                            showBottomBar = false
                            settingsStack.add(AppManagementKey)
                        },
                        onNavigateToTrustedNetworks = {
                            showBottomBar = false
                            settingsStack.add(TrustedNetworksKey)
                        },
                        onNavigateToWireGuardImport = {
                            showBottomBar = false
                            settingsStack.add(WireGuardImportKey)
                        },
                        onNavigateToFilterSetup = {
                            showBottomBar = false
                            settingsStack.add(FilterKey)
                        },
                        hasVpnInBottomBar = bottomBarScreens.contains(BottomBarScreen.Proxy),
                        hasFilterInBottomBar = bottomBarScreens.contains(BottomBarScreen.FilterSetup),
                        onNavigateToHttpsFiltering = {
                            showBottomBar = false
                            settingsStack.add(HttpsFilteringKey)
                        },
                        onNavigateToCertWizard = {
                            showBottomBar = false
                            settingsStack.add(CertInstallationWizardKey)
                        },
                        onNavigateToDNSProvider = {
                            showBottomBar = false
                            settingsStack.add(DnsProviderKey)
                        },
                        onNavigateToGeoIp = {
                            showBottomBar = false
                            settingsStack.add(GeoIpKey)
                        },
                        onNavigateToTailscale = {
                            showBottomBar = false
                            settingsStack.add(TailscaleKey)
                        },
                        externalDetailPane = pendingSettingsPane,
                        onExternalDetailPaneConsumed = { pendingSettingsPane = null }
                    )
                }
                entry<StatisticsKey> {
                    StatisticsScreen(
                        onNavigateBack = {
                            safePop(homeStack)
                        },
                        onNavigateToFilterDetail = { filterId ->
                            showBottomBar = false
                            homeStack.add(FilterDetailKey(filterId))
                        }
                    )
                }
                entry<LogsKey> {
                    LogsScreen(
                        initialFilterStatus = it.filterStatus,
                        initialSearchQuery = it.searchQuery,
                        onNavigateBack = {
                            safePop(homeStack)
                        }
                    )
                }
                entry<FilterDetailKey> {
                    FilterDetailScreen(
                        filterId = it.filterId,
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<FilterOverlapKey> {
                    FilterOverlapScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<AboutKey> {
                    AboutScreen(
                        onNavigateBack = {
                            safePop(settingsStack)
                        }
                    )
                }
                entry<AppearanceThemeKey> {
                    AppearanceThemeScreen(
                        onNavigateBack = {
                            safePop(settingsStack)
                        }
                    )
                }
                entry<AppearanceNavigationKey> {
                    AppearanceNavigationScreen(
                        onNavigateBack = {
                            safePop(settingsStack)
                        }
                    )
                }
                entry<AppearanceLanguageKey> {
                    AppearanceLanguageScreen(
                        onNavigateBack = {
                            safePop(settingsStack)
                        }
                    )
                }
                entry<AppManagementKey> {
                    AppManagementScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<DnsProviderKey> {
                    DnsProviderScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<TrustedNetworksKey> {
                    app.pwhs.blockads.ui.trustednetworks.TrustedNetworksScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<ProxyVpnKey> {
                    ProxyVpnScreen(
                        canNavigateBack = currentBackStack.size > 1,
                        onNavigateBack = {
                            safePop(currentBackStack)
                        },
                        onEditProfile = { profileId ->
                            currentBackStack.add(WireGuardEditKey(profileId))
                        },
                        onEditProxyProfile = { profileId ->
                            currentBackStack.add(ProxyEditKey(profileId))
                        },
                        onAddCustomProxy = {
                            currentBackStack.add(ProxyEditKey(null))
                        },
                        onNavigateToVpnSettings = {
                            currentBackStack.add(VpnSettingsKey)
                        },
                    )
                }
                entry<WireGuardImportKey> {
                    ProxyVpnScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        },
                        onEditProfile = { profileId ->
                            currentBackStack.add(WireGuardEditKey(profileId))
                        },
                        onEditProxyProfile = { profileId ->
                            currentBackStack.add(ProxyEditKey(profileId))
                        },
                        onAddCustomProxy = {
                            currentBackStack.add(ProxyEditKey(null))
                        },
                        onNavigateToVpnSettings = {
                            currentBackStack.add(VpnSettingsKey)
                        },
                    )
                }
                entry<VpnSettingsKey> {
                    VpnSettingsScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<WireGuardEditKey> { key ->
                    WireGuardEditScreen(
                        profileId = key.profileId,
                        onNavigateBack = {
                            safePop(currentBackStack)
                        },
                    )
                }
                entry<ProxyEditKey> { key ->
                    ProxyEditScreen(
                        profileId = key.profileId,
                        onNavigateBack = {
                            safePop(currentBackStack)
                        },
                    )
                }
                entry<HttpsFilteringKey> {
                    HttpsFilteringScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        },
                        onNavigateToWizard = {
                            currentBackStack.add(CertInstallationWizardKey)
                        }
                    )
                }
                entry<CertInstallationWizardKey> {
                    CertInstallationWizardScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<TailscaleKey> {
                    TailscaleScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<BrowserKey> { key ->
                    BrowserScreen(
                        initialUrl = key.initialUrl,
                        onCloseBrowser = {
                            safePop(currentBackStack)
                        },
                        onNavigateToElementRules = {
                            currentBackStack.add(ElementRulesKey)
                        }
                    )
                }
                entry<ElementRulesKey> {
                    ElementRulesScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
                entry<GeoIpKey> {
                    GeoIpScreen(
                        onNavigateBack = {
                            safePop(currentBackStack)
                        }
                    )
                }
            }
            )
        }

        val isRightRail = navRailPosition == AppPreferences.NAV_POSITION_RIGHT
        val showFloatingBottomOverlay = !useNavRail && floatingCapsuleNav && showBottomBar
        val showDockedBottomBar = !useNavRail && !floatingCapsuleNav && showBottomBar
        val floatingBottomPadding = if (showFloatingBottomOverlay) {
            if (showBottomNavLabels) 88.dp else 80.dp
        } else {
            0.dp
        }

        CompositionLocalProvider(LocalFloatingBottomBarPadding provides floatingBottomPadding) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (showDockedBottomBar) {
                        BlockAdsNavigationBar(
                            screens = bottomBarScreens,
                            currentTab = currentTab,
                            firewallEnabled = firewallEnabled,
                            showLabels = showBottomNavLabels,
                            floatingCapsule = false,
                            onSelectTab = { currentTab = it }
                        )
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(bottom = innerPadding.calculateBottomPadding())
                            .consumeWindowInsets(PaddingValues(bottom = innerPadding.calculateBottomPadding()))
                    ) {
                        if (useNavRail && !isRightRail && showBottomBar) {
                            BlockAdsNavigationRail(
                                screens = bottomBarScreens,
                                currentTab = currentTab,
                                firewallEnabled = firewallEnabled,
                                showLabels = showBottomNavLabels,
                                floatingCapsule = floatingCapsuleNav,
                                onSelectTab = { currentTab = it }
                            )
                            if (!floatingCapsuleNav) {
                                VerticalDivider(
                                    modifier = Modifier.statusBarsPadding(),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                        renderNavDisplay(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        if (useNavRail && isRightRail && showBottomBar) {
                            if (!floatingCapsuleNav) {
                                VerticalDivider(
                                    modifier = Modifier.statusBarsPadding(),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                            }
                            BlockAdsNavigationRail(
                                screens = bottomBarScreens,
                                currentTab = currentTab,
                                firewallEnabled = firewallEnabled,
                                showLabels = showBottomNavLabels,
                                floatingCapsule = floatingCapsuleNav,
                                onSelectTab = { currentTab = it }
                            )
                        }
                    }

                    if (showFloatingBottomOverlay) {
                        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                            BlockAdsNavigationBar(
                                screens = bottomBarScreens,
                                currentTab = currentTab,
                                firewallEnabled = firewallEnabled,
                                showLabels = showBottomNavLabels,
                                floatingCapsule = true,
                                onSelectTab = { currentTab = it }
                            )
                        }
                    }
                }
            }
        }
    }

    if (updateController.supportsInAppUpdate) {
        AppUpdateDialog(
            state = updateState,
            onStartUpdate = {
                val activity = context.findActivity() ?: return@AppUpdateDialog
                updateScope.launch { updateController.startUpdate(activity) }
            },
            onCompleteUpdate = { updateController.completeUpdate() },
            onDismiss = { skip ->
                updateScope.launch { updateController.dismissUpdate(skip) }
            },
        )
    }
}