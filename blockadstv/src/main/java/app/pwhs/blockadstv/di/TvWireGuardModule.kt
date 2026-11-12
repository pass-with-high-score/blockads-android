package app.pwhs.blockadstv.di

import app.pwhs.blockadstv.data.datastore.WireGuardPreferences
import app.pwhs.blockadstv.data.geoip.GeoIpManager
import app.pwhs.blockadstv.ui.screens.wireguard.WireGuardViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val tvWireGuardModule = module {
    single { WireGuardPreferences(androidContext()) }
    single { GeoIpManager(androidContext(), get()) }
    viewModel { WireGuardViewModel(get(), get(), get()) }
}
