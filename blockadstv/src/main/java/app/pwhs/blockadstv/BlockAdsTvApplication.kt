package app.pwhs.blockadstv

import android.app.Application
import app.pwhs.blockadstv.di.tvAppModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import timber.log.Timber

class BlockAdsTvApplication : Application() {

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
    }
}
