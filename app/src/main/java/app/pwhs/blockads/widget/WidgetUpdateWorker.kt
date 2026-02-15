package app.pwhs.blockads.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class WidgetUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "widget_update"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            AdBlockGlanceWidget().updateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
