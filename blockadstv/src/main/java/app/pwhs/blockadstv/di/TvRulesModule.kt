package app.pwhs.blockadstv.di

import app.pwhs.blockadstv.data.TvDatabase
import app.pwhs.blockadstv.data.datastore.RulesPreferences
import app.pwhs.blockadstv.data.rules.TvRuleStorage
import app.pwhs.blockadstv.data.rules.TvRuleSyncRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val tvRulesModule = module {
    single { get<TvDatabase>().configDao() }
    single { RulesPreferences(androidContext()) }
    single { TvRuleStorage(androidContext()) }
    single {
        TvRuleSyncRepository(
            context = androidContext(),
            storage = get(),
            client = get(),
            rulesPrefs = get(),
        )
    }
}
