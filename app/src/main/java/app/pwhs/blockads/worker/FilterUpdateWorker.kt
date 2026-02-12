package app.pwhs.blockads.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.FilterListRepository
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class FilterUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val filterListRepository: FilterListRepository by inject()
    private val appPreferences: AppPreferences by inject()

    companion object {
        const val WORK_NAME = "filter_update_work"
        private const val NOTIFICATION_CHANNEL_ID = "filter_update_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        return try {
            // Check notification preference
            val notificationType = appPreferences.autoUpdateNotification.first()

            // Update all enabled filters
            val result = filterListRepository.loadAllEnabledFilters()

            result.fold(
                onSuccess = { totalDomains ->
                    // Show notification based on preference
                    when (notificationType) {
                        AppPreferences.NOTIFICATION_NORMAL -> {
                            showUpdateNotification(
                                title = applicationContext.getString(R.string.filter_update_success),
                                message = applicationContext.getString(
                                    R.string.filter_update_success_message,
                                    totalDomains
                                ),
                                isSuccess = true
                            )
                        }

                        AppPreferences.NOTIFICATION_SILENT -> {
                            showUpdateNotification(
                                title = applicationContext.getString(R.string.filter_update_success),
                                message = applicationContext.getString(
                                    R.string.filter_update_success_message,
                                    totalDomains
                                ),
                                isSuccess = true,
                                silent = true
                            )
                        }

                        AppPreferences.NOTIFICATION_NONE -> {
                            // No notification
                        }
                    }
                    Result.success()
                },
                onFailure = { error ->
                    // Only show error notifications if not set to NONE
                    if (notificationType != AppPreferences.NOTIFICATION_NONE) {
                        showUpdateNotification(
                            title = applicationContext.getString(R.string.filter_update_failed),
                            message = error.message
                                ?: applicationContext.getString(R.string.filter_update_failed_message),
                            isSuccess = false
                        )
                    }
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun showUpdateNotification(
        title: String,
        message: String,
        isSuccess: Boolean,
        silent: Boolean = false
    ) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.filter_update_notification_channel),
                if (silent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    applicationContext.getString(R.string.filter_update_notification_channel_desc)
                if (silent) {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(if (isSuccess) R.drawable.ic_check else R.drawable.ic_error)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply {
                if (silent) {
                    setSilent(true)
                }
            }
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
