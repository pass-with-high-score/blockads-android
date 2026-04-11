package app.pwhs.blockadstv

import android.app.Application
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.di.tvAppModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import timber.log.Timber

class BlockAdsTvApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        startKoin {
            androidLogger()
            androidContext(this@BlockAdsTvApplication)
            modules(tvAppModule)
        }

        // Auto-fetch filter lists from GitHub on app launch
        // so filters are available immediately without starting VPN first
        val filterRepo: FilterListRepository by inject()
        appScope.launch {
            try {
                Timber.d("Auto-seeding filter lists on app launch...")
                filterRepo.seedDefaultsIfNeeded()
                val result = filterRepo.loadAllEnabledFilters()
                Timber.d("Auto-seed complete: ${result.getOrDefault(0)} rules loaded")
            } catch (e: Exception) {
                Timber.e(e, "Auto-seed failed")
            }
        }
    }
}
