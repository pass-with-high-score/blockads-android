package app.pwhs.blockads

import android.app.Application
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.di.appModule
import app.pwhs.blockads.worker.DailySummaryScheduler
import app.pwhs.blockads.worker.FilterUpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import timber.log.Timber
import timber.log.Timber.DebugTree


class BlockAdsApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@BlockAdsApplication)
            modules(appModule)
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }

        // Schedule auto-update for filter lists after Koin is initialized
        val appPreferences: AppPreferences by inject()
        applicationScope.launch {
            FilterUpdateScheduler.scheduleFilterUpdate(this@BlockAdsApplication, appPreferences)
            FilterUpdateScheduler.scheduleUpdateCheck(this@BlockAdsApplication)

            // Schedule daily summary only if enabled
            if (appPreferences.dailySummaryEnabled.first()) {
                DailySummaryScheduler.scheduleDailySummary(this@BlockAdsApplication)
            }
        }
    }
}
