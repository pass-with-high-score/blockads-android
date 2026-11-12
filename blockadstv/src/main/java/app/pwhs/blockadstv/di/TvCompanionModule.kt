package app.pwhs.blockadstv.di

import app.pwhs.blockadstv.companion.CompanionBackupHelper
import app.pwhs.blockadstv.companion.LocalWebCompanionServer
import app.pwhs.blockadstv.ui.screens.companion.WebCompanionViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val tvCompanionModule = module {
    single {
        CompanionBackupHelper(
            tvPrefs = get(),
            protectionPrefs = get(),
            filterListDao = get(),
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            firewallRuleDao = get(),
        )
    }
    single {
        LocalWebCompanionServer(
            wireGuardPrefs = get(),
            tvPrefs = get(),
            subscriptionManager = get(),
            backupHelper = get(),
        )
    }
    viewModel { WebCompanionViewModel(get()) }
}
