package app.pwhs.blockadstv.di

import app.pwhs.blockadstv.ui.screens.home.TvStatsViewModel
import app.pwhs.blockadstv.utils.SettingsBackupManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val tvStatsBackupModule = module {
    single {
        SettingsBackupManager(
            context = androidContext(),
            tvPrefs = get(),
            protectionPrefs = get(),
            rulesPrefs = get(),
            wgPrefs = get(),
            filterListDao = get(),
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            firewallRuleDao = get(),
            configDao = get(),
            filterRepo = get(),
        )
    }
    viewModel { TvStatsViewModel(get(), get()) }
}
