package app.pwhs.blockads.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object DailySummaryScheduler {

    /**
     * Schedules a one-time daily summary work for the next 21:00.
     * The worker itself reschedules the next run after completion,
     * avoiding drift issues with periodic work.
     */
    fun scheduleDailySummary(context: Context) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val workRequest = OneTimeWorkRequestBuilder<DailySummaryWorker>()
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DailySummaryWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun cancelDailySummary(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailySummaryWorker.WORK_NAME)
    }
}
