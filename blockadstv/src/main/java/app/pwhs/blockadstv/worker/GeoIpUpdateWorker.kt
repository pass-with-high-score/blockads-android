package app.pwhs.blockadstv.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pwhs.blockadstv.data.datastore.WireGuardPreferences
import app.pwhs.blockadstv.data.geoip.GeoIpManager
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

class GeoIpUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val geoIpManager: GeoIpManager by inject()
    private val wireGuardPrefs: WireGuardPreferences by inject()

    companion object {
        const val WORK_NAME = "tv_geoip_update_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<GeoIpUpdateWorker>(
                7, TimeUnit.DAYS,
                1, TimeUnit.DAYS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Timber.i("Scheduled periodic TV GeoIP database update worker (weekly)")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val autoUpdate = wireGuardPrefs.geoIpAutoUpdate.first()
            if (!autoUpdate) {
                Timber.d("TV GeoIP auto-update is disabled, skipping")
                return Result.success()
            }

            val customUrl = wireGuardPrefs.geoIpCustomUrl.first().trim().takeIf { it.isNotEmpty() }
            val result = geoIpManager.checkAndUpdate(customUrl = customUrl, force = false)
            if (result.isSuccess) Result.success() else Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "TV GeoIP update worker failed")
            Result.retry()
        }
    }
}
