package app.pwhs.blockadstv.update

import app.pwhs.blockadstv.data.datastore.TvPreferences
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val tvUpdateModule = module {
    single<TvUpdateController> {
        GitHubTvUpdateController(
            context = androidContext(),
            httpClient = get(),
            prefs = get<TvPreferences>(),
            modulePrefix = "blockadstv-",
        )
    }
}
