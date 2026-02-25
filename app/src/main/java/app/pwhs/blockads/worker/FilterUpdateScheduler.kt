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

    fun scheduleUpdateCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    fun cancelUpdateCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
    }
}
