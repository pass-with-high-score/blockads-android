package app.pwhs.blockads

import android.app.Application
import app.pwhs.blockads.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class BlockAdsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@BlockAdsApplication)
            modules(appModule)
        }
    }
}
