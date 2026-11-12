package app.pwhs.blockadstv.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockadstv.service.TvVpnService
import timber.log.Timber

class VpnResumeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "vpn_resume_work"
    }

    override suspend fun doWork(): Result {
        return try {
            TvVpnService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume VPN")
            Result.retry()
        }
    }
}
