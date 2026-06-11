package app.pwhs.blockads.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.about.AboutScreen
import app.pwhs.blockads.ui.appearance.AppearanceScreen
import app.pwhs.blockads.ui.appmanagement.AppManagementScreen
import app.pwhs.blockads.ui.customrules.CustomRulesScreen
import app.pwhs.blockads.ui.data.AboutKey
import app.pwhs.blockads.ui.data.AppManagementKey
import app.pwhs.blockads.ui.data.AppearanceKey
import app.pwhs.blockads.ui.data.BottomBarScreen
import app.pwhs.blockads.ui.data.CustomRuleKey
import app.pwhs.blockads.ui.data.DnsProviderKey
import app.pwhs.blockads.ui.data.DomainRulesKey
import app.pwhs.blockads.ui.data.FilterDetailKey
import app.pwhs.blockads.ui.data.FilterKey
import app.pwhs.blockads.ui.data.FireWallKey
import app.pwhs.blockads.ui.data.HomeKey
import app.pwhs.blockads.ui.data.HttpsFilteringKey
import app.pwhs.blockads.ui.data.LogsKey
import app.pwhs.blockads.ui.data.ProfileKey
import app.pwhs.blockads.ui.data.SettingsKey
import app.pwhs.blockads.ui.data.StatisticsKey
import app.pwhs.blockads.ui.data.WhiteListAppKey
import app.pwhs.blockads.ui.data.WireGuardEditKey
import app.pwhs.blockads.ui.data.WireGuardImportKey
import app.pwhs.blockads.ui.dnsprovider.DnsProviderScreen
import app.pwhs.blockads.ui.domainrules.DomainRulesScreen
import app.pwhs.blockads.ui.filter.FilterSetupScreen
import app.pwhs.blockads.ui.filter.detail.FilterDetailScreen
import app.pwhs.blockads.ui.firewall.FirewallScreen
import app.pwhs.blockads.ui.home.HomeScreen
import app.pwhs.blockads.ui.httpsfiltering.HttpsFilteringScreen
import app.pwhs.blockads.ui.logs.LogsScreen
import app.pwhs.blockads.ui.profile.ProfileScreen
import app.pwhs.blockads.ui.settings.SettingsScreen
import app.pwhs.blockads.ui.statistics.StatisticsScreen
import app.pwhs.blockads.ui.whitelist.AppWhitelistScreen
import app.pwhs.blockads.ui.wireguard.WireGuardEditScreen
import app.pwhs.blockads.ui.wireguard.WireGuardImportScreen
import org.koin.compose.koinInject

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun HomeApp(
    onRequestVpnPermission: () -> Unit = {},
    onShowVpnConflictDialog: () -> Unit = {}
) {
    val appPrefs: AppPreferences = koinInject()
    val showBottomNavLabels by appPrefs.showBottomNavLabels.collectAsStateWithLifecycle(
        initialValue = true,
    )
    val homeStack = rememberNavBackStack(HomeKey)
    val filterStack = rememberNavBackStack(FilterKey)
    val firewallStack = rememberNavBackStack(FireWallKey)
    val domainRuleStack = rememberNavBackStack(DomainRulesKey)
    val settingsStack = rememberNavBackStack(SettingsKey)
    var currentTab by rememberSaveable { mutableStateOf(BottomBarScreen.Home) }

    val currentBackStack = when (currentTab) {
        BottomBarScreen.Home -> homeStack
        BottomBarScreen.FilterSetup -> filterStack
        BottomBarScreen.Firewall -> firewallStack
        BottomBarScreen.DomainRule -> domainRuleStack
        BottomBarScreen.Settings -> settingsStack
    }

    val bottomBarScreens = listOf(
        BottomBarScreen.Home,
        BottomBarScreen.FilterSetup,
        BottomBarScreen.Firewall,
        BottomBarScreen.DomainRule,
        BottomBarScreen.Settings
    )
    var showBottomBar by rememberSaveable { mutableStateOf(true) }
    Scaffold(
        bottomBar = {
            if (!showBottomBar) return@Scaffold
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                bottomBarScreens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentBackStack == when (screen) {
                            BottomBarScreen.Home -> homeStack
                            BottomBarScreen.FilterSetup -> filterStack
                            BottomBarScreen.Firewall -> firewallStack
                            BottomBarScreen.DomainRule -> domainRuleStack
                            BottomBarScreen.Settings -> settingsStack
                        },
                        onClick = {
                            currentTab = screen
                        },
                        icon = {
                            Icon(
                                painter = painterResource(screen.icon),
                                contentDescription = stringResource(screen.labelRes),
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = if (showBottomNavLabels) {
                            {
                                Text(
                                    text = stringResource(screen.labelRes),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = LocalTextStyle.current.copy(
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        } else null,
                        alwaysShowLabel = showBottomNavLabels,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) {
        // When on a non-Home tab root, back should switch to Home tab instead of exiting
        BackHandler(enabled = currentTab != BottomBarScreen.Home && currentBackStack.size <= 1) {
            currentTab = BottomBarScreen.Home
            showBottomBar = true
        }

        NavDisplay(
            backStack = currentBackStack,
            onBack = {
                if (currentBackStack.size > 1) currentBackStack.removeLastOrNull()
                showBottomBar = currentBackStack.size <= 1
            },
            entryProvider = entryProvider {
                entry<HomeKey> {
                    HomeScreen(
                        onShowVpnConflictDialog = onShowVpnConflictDialog,
                        onRequestVpnPermission = onRequestVpnPermission,
                        onNavigateToLogScreen = {
                            showBottomBar = false
                            homeStack.add(LogsKey)
                        },
                        onNavigateToStatisticsScreen = {
                            showBottomBar = false
                            homeStack.add(StatisticsKey)
                        },
                        onNavigateToProfileScreen = {
                            showBottomBar = false
                            homeStack.add(ProfileKey)
                        }
                    )
                }
                entry<FilterKey> {
                    FilterSetupScreen(
                        onNavigateToFilterDetail = { filterId ->
                            showBottomBar = false
                            filterStack.add(FilterDetailKey(filterId))
                        },
                        onNavigateToCustomRules = {
                            showBottomBar = false
                            filterStack.add(CustomRuleKey)
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
                        onNavigateToFilterSetup = {
                            currentTab = BottomBarScreen.FilterSetup
                        },
                        onNavigateToWhitelistApps = {
                            showBottomBar = false
                            settingsStack.add(WhiteListAppKey)
                        },
                        onNavigateToWireGuardImport = {
                            showBottomBar = false
                            settingsStack.add(WireGuardImportKey)
                        },
                        onNavigateToHttpsFiltering = {
                            showBottomBar = false
                            settingsStack.add(HttpsFilteringKey)
                        },
                        onNavigateToDNSProvider = {
                            showBottomBar = false
                            settingsStack.add(DnsProviderKey)
                        }
                    )
                }
                entry<StatisticsKey> {
                    StatisticsScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            homeStack.removeLastOrNull()
                        }
                    )
                }
                entry<LogsKey> {
                    LogsScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            homeStack.removeLastOrNull()
                        }
                    )
                }
                entry<ProfileKey> {
                    ProfileScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            homeStack.removeLastOrNull()
                        }
                    )
                }
                entry<FilterDetailKey> {
                    FilterDetailScreen(
                        filterId = it.filterId,
                        onNavigateBack = {
                            showBottomBar = true
                            filterStack.removeLastOrNull()
                        }
                    )
                }
                entry<CustomRuleKey> {
                    CustomRulesScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            filterStack.removeLastOrNull()
                        }
                    )
                }
                entry<AboutKey> {
                    AboutScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<AppearanceKey> {
                    AppearanceScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<AppManagementKey> {
                    AppManagementScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<DnsProviderKey> {
                    DnsProviderScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<WhiteListAppKey> {
                    AppWhitelistScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<WireGuardImportKey> {
                    WireGuardImportScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        },
                        onEditProfile = { profileId ->
                            settingsStack.add(WireGuardEditKey(profileId))
                        },
                    )
                }
                entry<WireGuardEditKey> { key ->
                    WireGuardEditScreen(
                        profileId = key.profileId,
                        onNavigateBack = {
                            settingsStack.removeLastOrNull()
                        },
                    )
                }
                entry<HttpsFilteringKey> {
                    HttpsFilteringScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
            }
        )
    }
}