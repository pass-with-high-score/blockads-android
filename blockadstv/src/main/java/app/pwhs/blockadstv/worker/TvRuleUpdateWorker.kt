package app.pwhs.blockadstv.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pwhs.blockadstv.data.rules.TvRuleSyncRepository
import app.pwhs.blockadstv.service.ServiceController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that syncs dynamic rule manifest and modular rules for :blockadstv.
 */
class TvRuleUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val repository: TvRuleSyncRepository by inject()

    companion object {
        const val WORK_NAME = "tv_rule_update_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<TvRuleUpdateWorker>(
                1, TimeUnit.DAYS,
                6, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Timber.i("Scheduled periodic TV rule update worker")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val rulesResult = repository.checkAndUpdate()
            val modulesResult = repository.checkAndUpdateModules()
            if (rulesResult.getOrDefault(false) || modulesResult.getOrDefault(false)) {
                ServiceController.requestRestart(applicationContext)
            }
            if (rulesResult.isSuccess) Result.success() else Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "TV rule update worker failed")
            Result.retry()
        }
    }
}
