package app.pwhs.blockadstv.di

import app.pwhs.blockadstv.data.datastore.ProtectionPreferences
import app.pwhs.blockadstv.service.TrustedNetworkManager
import app.pwhs.blockadstv.service.TvProtectionTunnelHelper
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val tvProtectionModule = module {
    single { ProtectionPreferences(androidContext()) }
    single { TrustedNetworkManager(androidContext(), get()) }
    single { TvProtectionTunnelHelper(androidContext(), get()) }
}
