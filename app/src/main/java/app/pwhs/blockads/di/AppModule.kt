package app.pwhs.blockads.di

import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.FilterListRepository
import app.pwhs.blockads.data.ProfileManager
import app.pwhs.blockads.dns.DohClient
import app.pwhs.blockads.dns.DotClient
import app.pwhs.blockads.ui.dnsprovider.DnsProviderViewModel
import app.pwhs.blockads.ui.filter.FilterSetupViewModel
import app.pwhs.blockads.ui.home.HomeViewModel
import app.pwhs.blockads.ui.logs.LogViewModel
import app.pwhs.blockads.ui.onboarding.OnboardingViewModel
import app.pwhs.blockads.ui.profile.ProfileViewModel
import app.pwhs.blockads.ui.settings.SettingsViewModel
import app.pwhs.blockads.ui.statistics.StatisticsViewModel
import app.pwhs.blockads.ui.whitelist.AppWhitelistViewModel
import app.pwhs.blockads.ui.appmanagement.AppManagementViewModel
import app.pwhs.blockads.ui.customrules.CustomRulesViewModel
import app.pwhs.blockads.ui.firewall.FirewallViewModel
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

    // DNS Clients
    single { DohClient(get()) }
    single { DotClient() }

    // Database
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().dnsLogDao() }
    single { get<AppDatabase>().filterListDao() }
    single { get<AppDatabase>().whitelistDomainDao() }
    single { get<AppDatabase>().dnsErrorDao() }
    single { get<AppDatabase>().customDnsRuleDao() }
    single { get<AppDatabase>().protectionProfileDao() }
    single { get<AppDatabase>().firewallRuleDao() }

    // Preferences
    single { AppPreferences(androidContext()) }

    // Repository
    single { FilterListRepository(androidContext(), get(), get(), get(), get()) }

    // Profile Manager
    single { ProfileManager(get(), get(), get(), get()) }

    // ViewModels
    viewModel { HomeViewModel(get(), get(), get()) }
    viewModel { StatisticsViewModel(get()) }
    viewModel { LogViewModel(get(), get(), get(), get()) }
    viewModel {
        SettingsViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            application = androidApplication()
        )
    }
    viewModel { FilterSetupViewModel(get(), get(), androidApplication()) }
    viewModel {
        AppWhitelistViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel { CustomRulesViewModel(get(), get(), androidApplication()) }
    viewModel {
        DnsProviderViewModel(
            appPrefs = get(),
            application = androidApplication()
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
        ProfileViewModel(
            profileManager = get(),
            profileDao = get(),
            filterListDao = get(),
            application = androidApplication()
        )
    }
    viewModel {
        FirewallViewModel(
            appPrefs = get(),
            firewallRuleDao = get(),
            application = androidApplication()
        )
    }
}

