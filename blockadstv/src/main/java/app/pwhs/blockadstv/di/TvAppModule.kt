package app.pwhs.blockadstv.di

import app.pwhs.blockadstv.data.TvDatabase
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.remote.FilterDownloadManager
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.ui.screens.home.TvHomeViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val tvAppModule = module {
    // HTTP client
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

    // Database
    single { TvDatabase.getInstance(androidContext()) }
    single { get<TvDatabase>().dnsLogDao() }
    single { get<TvDatabase>().filterListDao() }

    // Preferences
    single { TvPreferences(androidContext()) }

    // Repositories
    single { FilterDownloadManager(androidContext(), get()) }
    single { FilterListRepository(androidContext(), get(), get(), get()) }

    // ViewModels
    viewModel { TvHomeViewModel(get(), get(), get()) }
}
