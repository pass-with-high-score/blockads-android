package app.pwhs.blockads.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.ServiceController
import kotlinx.coroutines.flow.first
import timber.log.Timber

class RootProxyResumeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "root_proxy_resume_work"
    }

    override suspend fun doWork(): Result {
        return try {
            val appPrefs = AppPreferences(applicationContext)
            if (!appPrefs.protectionDesired.first()) {
                Timber.d("Skipping Root Proxy resume; protection is no longer desired")
                return Result.success()
            }
            ServiceController.requestStartNow(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume Root Proxy")
            Result.retry()
        }
    }
}
