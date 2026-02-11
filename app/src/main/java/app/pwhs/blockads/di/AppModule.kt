/*
 * BlockAds - Ad blocker for Android using local VPN-based DNS filtering
 * Copyright (C) 2025 The BlockAds Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.pwhs.blockads.di

import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.FilterListRepository
import app.pwhs.blockads.ui.filter.FilterSetupViewModel
import app.pwhs.blockads.ui.home.HomeViewModel
import app.pwhs.blockads.ui.logs.LogViewModel
import app.pwhs.blockads.ui.settings.SettingsViewModel
import app.pwhs.blockads.ui.whitelist.AppWhitelistViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    // HTTP Client
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
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().dnsLogDao() }
    single { get<AppDatabase>().filterListDao() }
    single { get<AppDatabase>().whitelistDomainDao() }
    single { get<AppDatabase>().dnsErrorDao() }

    // Preferences
    single { AppPreferences(androidContext()) }

    // Repository
    single { FilterListRepository(androidContext(), get(), get(), get()) }

    // ViewModels
    viewModel { HomeViewModel(get(), get()) }
    viewModel { LogViewModel(get(), get(), androidContext()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), androidContext()) }
    viewModel { FilterSetupViewModel(get(), androidContext()) }
    viewModel { AppWhitelistViewModel(get(), androidContext()) }
}

