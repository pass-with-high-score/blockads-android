package app.pwhs.blockads.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pwhs.blockads.data.geoip.GeoIpManager
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically checks for and downloads weekly GeoIP database updates.
 */
class GeoIpUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val geoIpManager: GeoIpManager by inject()
    private val appPreferences: app.pwhs.blockads.data.datastore.AppPreferences by inject()

    companion object {
        const val WORK_NAME = "geoip_update_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<GeoIpUpdateWorker>(
                7, TimeUnit.DAYS,
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("Scheduled periodic GeoIP database update worker (weekly)")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Timber.d("Starting background GeoIP database update work")
            val autoUpdate = appPreferences.geoIpAutoUpdate.first()
            if (!autoUpdate) {
                Timber.d("GeoIP auto-update is disabled by user, skipping")
                return Result.success()
            }

            val customUrl = appPreferences.geoIpCustomUrl.first()
                .trim().takeIf { it.isNotEmpty() }

            val result = geoIpManager.checkAndUpdate(customUrl = customUrl, force = false)
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "GeoIP update worker failed")
            Result.retry()
        }
    }
}
