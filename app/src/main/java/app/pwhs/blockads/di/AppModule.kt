package app.pwhs.blockads.di

import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.FilterListRepository
import app.pwhs.blockads.dns.DohClient
import app.pwhs.blockads.dns.DotClient
import app.pwhs.blockads.ui.dnsprovider.DnsProviderViewModel
import app.pwhs.blockads.ui.filter.FilterSetupViewModel
import app.pwhs.blockads.ui.home.HomeViewModel
import app.pwhs.blockads.ui.logs.LogViewModel
import app.pwhs.blockads.ui.settings.SettingsViewModel
import app.pwhs.blockads.ui.statistics.StatisticsViewModel
import app.pwhs.blockads.ui.whitelist.AppWhitelistViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

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

    // Preferences
    single { AppPreferences(androidContext()) }

    // Repository
    single { FilterListRepository(androidContext(), get(), get(), get(), get()) }

    // ViewModels
    viewModel { HomeViewModel(get(), get()) }
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
            application = androidApplication()
        )
    }
    viewModel { FilterSetupViewModel(get(), get()) }
    viewModel {
        AppWhitelistViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel { app.pwhs.blockads.ui.customrules.CustomRulesViewModel(get(), get()) }
    viewModel {
        DnsProviderViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
}

