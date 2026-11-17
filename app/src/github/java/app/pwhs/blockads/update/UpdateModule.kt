package app.pwhs.blockads.update

import app.pwhs.blockads.data.datastore.AppPreferences
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val updateModule = module {
    single<AppUpdateController> {
        GitHubUpdateController(
            context = androidContext(),
            httpClient = get(),
            prefs = get<AppPreferences>(),
            modulePrefix = "app-",
        )
    }
}
