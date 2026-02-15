package app.pwhs.blockads.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockads.service.AdBlockVpnService

class VpnResumeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "VpnResumeWorker"
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
            Log.e(TAG, "Failed to resume VPN", e)
            Result.retry()
        }
    }
}
