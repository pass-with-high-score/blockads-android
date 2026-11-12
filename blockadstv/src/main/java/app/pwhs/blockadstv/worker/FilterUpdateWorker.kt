package app.pwhs.blockadstv.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.service.ServiceController
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class FilterUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val filterListRepository: FilterListRepository by inject()

    companion object {
        const val WORK_NAME = "filter_update_work"
        private const val NOTIFICATION_CHANNEL_ID = "filter_update_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        return try {
            filterListRepository.fetchAndSyncRemoteFilterLists()
            val result = filterListRepository.loadAllEnabledFilters(forceDownload = true)

            if (result.isSuccess) {
                val totalCount = result.getOrDefault(0)
                ServiceController.requestRestart(applicationContext)
                showUpdateNotification(
                    title = applicationContext.getString(R.string.filter_update_success),
                    message = applicationContext.getString(
                        R.string.filter_update_success_message,
                        totalCount
                    ),
                    isSuccess = true
                )
                Result.success()
            } else {
                val failureMessage = result.exceptionOrNull()?.message
                    ?: applicationContext.getString(R.string.filter_update_failed_message)
                showUpdateNotification(
                    title = applicationContext.getString(R.string.filter_update_failed),
                    message = failureMessage,
                    isSuccess = false
                )
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "Filter update failed")
            Result.failure()
        }
    }

    private fun showUpdateNotification(
        title: String,
        message: String,
        isSuccess: Boolean
    ) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.filter_update_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    applicationContext.getString(R.string.filter_update_notification_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(if (isSuccess) R.drawable.ic_check else R.drawable.ic_error)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
