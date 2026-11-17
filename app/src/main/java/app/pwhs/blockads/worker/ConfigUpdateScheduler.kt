package app.pwhs.blockads.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object ConfigUpdateScheduler {

    suspend fun scheduleConfigUpdate(context: Context, appPreferences: AppPreferences) {
        val enabled = appPreferences.autoUpdateEnabled.first()
        if (!enabled) {
            cancelConfigUpdate(context)
            return
        }

        val frequency = appPreferences.autoUpdateFrequency.first()
        if (frequency == AppPreferences.UPDATE_FREQUENCY_MANUAL) {
            cancelConfigUpdate(context)
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
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ConfigUpdateWorker>(
            intervalHours,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ConfigUpdateWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    fun cancelConfigUpdate(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ConfigUpdateWorker.WORK_NAME)
    }
}
