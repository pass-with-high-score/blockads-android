package app.pwhs.blockads.ui.browser.rules

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically checks for and downloads dynamic browser filter updates.
 */
class BrowserRuleUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val repository: BrowserRuleRepository by inject()

    companion object {
        const val WORK_NAME = "browser_rule_update_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BrowserRuleUpdateWorker>(
                1, TimeUnit.DAYS,
                6, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("Scheduled periodic browser rule update worker")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Timber.d("Starting background browser rule update work")
            val result = repository.checkAndUpdate()
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "Browser rule update worker failed")
            Result.retry()
        }
    }
}
