package app.pwhs.blockads.di

import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.dao.FirewallRuleDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.remote.FilterDownloadManager
import app.pwhs.blockads.data.remote.api.CustomFilterApi
import app.pwhs.blockads.data.repository.CustomFilterManager
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.ui.dnsprovider.DnsProviderViewModel
import app.pwhs.blockads.ui.filter.detail.FilterDetailViewModel
import app.pwhs.blockads.ui.filter.FilterSetupViewModel
import app.pwhs.blockads.ui.home.HomeViewModel
import app.pwhs.blockads.ui.logs.LogViewModel
import app.pwhs.blockads.ui.onboarding.OnboardingViewModel
import app.pwhs.blockads.ui.config.ConfigViewModel
import app.pwhs.blockads.ui.appearance.AppearanceViewModel
import app.pwhs.blockads.ui.settings.SettingsViewModel
import app.pwhs.blockads.ui.statistics.StatisticsViewModel
import app.pwhs.blockads.ui.appmanagement.AppManagementViewModel
import app.pwhs.blockads.ui.customrules.CustomRulesViewModel
import app.pwhs.blockads.ui.domainrules.DomainRulesViewModel
import app.pwhs.blockads.ui.firewall.FirewallViewModel
import app.pwhs.blockads.ui.splash.SplashViewModel
import app.pwhs.blockads.ui.wireguard.WireGuardEditViewModel
import app.pwhs.blockads.ui.wireguard.WireGuardImportViewModel
import app.pwhs.blockads.ui.httpsfiltering.HttpsFilteringViewModel
import app.pwhs.blockads.ui.httpsfiltering.wizard.CertInstallationWizardViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import timber.log.Timber

val appModule = module {

    // HTTP Client
    single {
        HttpClient(CIO) {
            engine {
                requestTimeout = 60_000
                endpoint {
                    connectTimeout = 30_000
                }
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Timber.d(message)
                    }
                }
                val logLevel = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
                level = logLevel
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
            }
        }
    }

    // DNS Clients (Removed - now handled by Go tunnel)

    // Database
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().dnsLogDao() }
    single { get<AppDatabase>().filterListDao() }
    single { get<AppDatabase>().whitelistDomainDao() }
    single { get<AppDatabase>().dnsErrorDao() }
    single { get<AppDatabase>().customDnsRuleDao() }
    single { get<AppDatabase>().configDao() }
    single { get<AppDatabase>().firewallRuleDao() }
    single { get<AppDatabase>().elementRuleDao() }

    // Preferences
    single { AppPreferences(androidContext()) }

    // Repository
    single { FilterDownloadManager(androidContext(), get()) }
    single {
        FilterListRepository(
            context = androidContext(),
            filterListDao = get(),
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            client = get(),
            downloadManager = get()
        )
    }
    single { CustomFilterApi(get()) }
    single {
        CustomFilterManager(
            context = androidContext(),
            client = get(),
            filterListDao = get(),
            customFilterApi = get()
        )
    }

    // Browser Dynamic Rules & Search Suggestions
    single { app.pwhs.blockads.ui.browser.rules.BrowserRuleStorage(androidContext()) }
    single<app.pwhs.blockads.ui.browser.rules.BrowserRuleRepository> {
        app.pwhs.blockads.ui.browser.rules.BrowserRuleRepositoryImpl(
            storage = get(),
            client = get()
        )
    }
    single<app.pwhs.blockads.ui.browser.data.SearchSuggestionRepository> {
        app.pwhs.blockads.ui.browser.data.SearchSuggestionRepositoryImpl(
            client = get()
        )
    }

    // ViewModels
    viewModel {
        HomeViewModel(
            appPrefs = get(),
            dnsLogDao = get(),
            filterRepo = get(),
            configDao = get(),
            filterListDao = get(),
            whitelistDomainDao = get(),
            customDnsRuleDao = get()
        )
    }
    viewModel { StatisticsViewModel(dnsLogDao = get(), filterListDao = get()) }
    viewModel {
        LogViewModel(
            dnsLogDao = get(),
            filterListDao = get(),
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            filterListRepository = get(),
            appPrefs = get(),
            application = androidApplication(),
            firewallRuleDao = get()
        )
    }
    single {
        app.pwhs.blockads.utils.SettingsBackupManager(
            context = androidApplication(),
            appPrefs = get(),
            filterListDao = get(),
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            firewallRuleDao = get(),
            configDao = get(),
            filterRepo = get()
        )
    }
    viewModel {
        SettingsViewModel(
            appPrefs = get(),
            filterRepo = get(),
            dnsLogDao = get(),
            whitelistDomainDao = get(),
            filterListDao = get(),
            customDnsRuleDao = get(),
            firewallRuleDao = get(),
            backupManager = get(),
            application = androidApplication()
        )
    }
    viewModel {
        FilterSetupViewModel(
            filterRepo = get(),
            filterListDao = get(),
            customFilterManager = get(),
            application = androidApplication(),
            appPreferences = get()
        )
    }
    viewModel { (filterId: Long) ->
        FilterDetailViewModel(
            filterId = filterId,
            filterListDao = get(),
            dnsLogDao = get(),
            filterRepo = get(),
            application = androidApplication(),
            customFilterManager = get()
        )
    }
    viewModel {
        app.pwhs.blockads.ui.trustednetworks.TrustedNetworksViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        CustomRulesViewModel(
            customDnsRuleDao = get(),
            filterListRepository = get(),
            application = androidApplication()
        )
    }
    viewModel {
        DnsProviderViewModel(
            appPrefs = get(),
            application = androidApplication(),
            configDao = get()
        )
    }
    viewModel {
        AppManagementViewModel(
            appPrefs = get(),
            dnsLogDao = get(),
            application = androidApplication(),
        )
    }
    viewModel {
        OnboardingViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        ConfigViewModel(
            configDao = get(),
            client = get(),
            application = androidApplication(),
            appPreferences = get(),
            customDnsRuleDao = get(),
            whitelistDomainDao = get(),
            filterListDao = get()
        )
    }
    viewModel {
        FirewallViewModel(
            appPrefs = get(),
            firewallRuleDao = get(),
            application = androidApplication()
        )
    }
    viewModel {
        AppearanceViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        SplashViewModel(
            appPrefs = get(),
        )
    }
    viewModel {
        DomainRulesViewModel(
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            filterRepo = get(),
            configDao = get(),
            application = androidApplication()
        )
    }
    viewModel {
        WireGuardImportViewModel(
            application = androidApplication()
        )
    }
    viewModel {
        WireGuardEditViewModel(
            application = androidApplication()
        )
    }
    viewModel {
        HttpsFilteringViewModel(
            application = androidApplication()
        )
    }
    viewModel {
        CertInstallationWizardViewModel(
            application = androidApplication()
        )
    }
    viewModel {
        app.pwhs.blockads.ui.browser.BrowserViewModel(
            application = androidApplication(),
            ruleRepository = get(),
            suggestionRepository = get(),
            elementRuleDao = get()
        )
    }
    viewModel {
        app.pwhs.blockads.ui.browser.elementrules.ElementRulesViewModel(
            application = androidApplication(),
            elementRuleDao = get()
        )
    }
}

