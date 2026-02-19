package app.pwhs.blockads.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockads.service.AdBlockVpnService
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
            val intent = Intent(applicationContext, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume VPN")
            Result.retry()
        }
    }
}
