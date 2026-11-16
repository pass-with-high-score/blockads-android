package app.pwhs.blockads.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.pwhs.blockads.ui.appearance.AppearanceScreen
import app.pwhs.blockads.ui.appmanagement.AppManagementScreen
import app.pwhs.blockads.ui.data.AboutKey
import app.pwhs.blockads.ui.data.AppManagementKey
import app.pwhs.blockads.ui.data.AppearanceKey
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
import app.pwhs.blockads.ui.home.HomeScreen
import app.pwhs.blockads.ui.home.component.FeatureBadgeKind
import app.pwhs.blockads.ui.home.component.HomeFeatureTipType
import app.pwhs.blockads.ui.httpsfiltering.HttpsFilteringScreen
import app.pwhs.blockads.ui.logs.LogsScreen
import app.pwhs.blockads.ui.settings.SettingsScreen
import app.pwhs.blockads.ui.statistics.StatisticsScreen
import app.pwhs.blockads.ui.wireguard.WireGuardEditScreen
import app.pwhs.blockads.ui.proxyvpn.ProxyVpnScreen
import app.pwhs.blockads.ui.vpnsettings.VpnSettingsScreen
import org.koin.compose.koinInject

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun HomeApp(
    onRequestVpnPermission: () -> Unit = {},
    onShowVpnConflictDialog: () -> Unit = {}
) {
    val context = LocalContext.current
    val appPrefs: AppPreferences = koinInject()
    val showBottomNavLabels by appPrefs.showBottomNavLabels.collectAsStateWithLifecycle(
        initialValue = true,
    )
    val firewallEnabled by appPrefs.firewallEnabled.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val secondaryTab by appPrefs.bottomNavSecondaryTab.collectAsStateWithLifecycle(
        initialValue = AppPreferences.BOTTOM_NAV_TAB_AUTO,
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

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
                        }
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
                        onNavigateToAppearance = {
                            showBottomBar = false
                            settingsStack.add(AppearanceKey)
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
                        }
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
                entry<AppearanceKey> {
                    AppearanceScreen(
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

        if (useNavRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (showBottomBar) {
                    BlockAdsNavigationRail(
                        screens = bottomBarScreens,
                        currentTab = currentTab,
                        firewallEnabled = firewallEnabled,
                        showLabels = showBottomNavLabels,
                        onSelectTab = { currentTab = it }
                    )
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
                renderNavDisplay(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        } else {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (showBottomBar) {
                        BlockAdsNavigationBar(
                            screens = bottomBarScreens,
                            currentTab = currentTab,
                            firewallEnabled = firewallEnabled,
                            showLabels = showBottomNavLabels,
                            onSelectTab = { currentTab = it }
                        )
                    }
                }
            ) { innerPadding ->
                renderNavDisplay(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                        .consumeWindowInsets(PaddingValues(bottom = innerPadding.calculateBottomPadding()))
                )
            }
        }
    }
}