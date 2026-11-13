package app.pwhs.blockads.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodically prunes DNS logs older than the user-configured retention period.
 */
class LogCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val dnsLogDao: DnsLogDao by inject()
    private val appPreferences: AppPreferences by inject()

    override suspend fun doWork(): Result {
        return try {
            val retentionDays = appPreferences.logRetentionDays.first()
            if (retentionDays > 0) {
                val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * MILLIS_PER_DAY)
                val pruned = dnsLogDao.deleteLogsOlderThan(cutoff)
                if (pruned > 0) {
                    Timber.d("LogCleanupWorker: pruned $pruned logs older than $retentionDays days")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "LogCleanupWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "dns_log_cleanup_work"
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        fun syncSchedule(context: Context, retentionDays: Int) {
            if (retentionDays > 0) {
                schedule(context)
            } else {
                cancel(context)
            }
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LogCleanupWorker>(
                24, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.d("Scheduled periodic DNS log cleanup worker (daily)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
