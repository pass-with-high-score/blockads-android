package app.pwhs.blockadstv.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object FilterUpdateScheduler {

    private const val DEFAULT_INTERVAL_HOURS = 24L

    fun scheduleFilterUpdate(
        context: Context,
        intervalHours: Long = DEFAULT_INTERVAL_HOURS
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
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
