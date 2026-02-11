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
package app.pwhs.blockads.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.pwhs.blockads.data.AppPreferences
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object FilterUpdateScheduler {

    suspend fun scheduleFilterUpdate(context: Context, appPreferences: AppPreferences) {
        val enabled = appPreferences.autoUpdateEnabled.first()
        
        if (!enabled) {
            cancelFilterUpdate(context)
            return
        }

        val frequency = appPreferences.autoUpdateFrequency.first()
        val wifiOnly = appPreferences.autoUpdateWifiOnly.first()

        // Don't schedule if set to manual
        if (frequency == AppPreferences.UPDATE_FREQUENCY_MANUAL) {
            cancelFilterUpdate(context)
            return
        }

        val intervalHours = when (frequency) {
            AppPreferences.UPDATE_FREQUENCY_6H -> 6L
            AppPreferences.UPDATE_FREQUENCY_12H -> 12L
            AppPreferences.UPDATE_FREQUENCY_24H -> 24L
            AppPreferences.UPDATE_FREQUENCY_48H -> 48L
            else -> 24L
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<FilterUpdateWorker>(
            intervalHours,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FilterUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    fun cancelFilterUpdate(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FilterUpdateWorker.WORK_NAME)
    }
}
