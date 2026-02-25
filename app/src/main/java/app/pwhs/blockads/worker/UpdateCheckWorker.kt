package app.pwhs.blockads.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.update.UpdateChecker
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val updateChecker: UpdateChecker by inject()
    private val appPreferences: AppPreferences by inject()

    companion object {
        const val WORK_NAME = "update_check_work"
        private const val NOTIFICATION_CHANNEL_ID = "app_update_channel"
        private const val NOTIFICATION_ID = 1002
    }

    override suspend fun doWork(): Result {
        return try {
            val checkEnabled = appPreferences.updateCheckEnabled.first()
            if (!checkEnabled) return Result.success()

            val updateInfo = updateChecker.checkForUpdate(applicationContext)
                ?: return Result.success()

            // Don't notify if user already dismissed this version
            val dismissedVersion = appPreferences.dismissedUpdateVersion.first()
            if (dismissedVersion == updateInfo.latestVersion) {
                return Result.success()
            }

            // Save latest available version
            appPreferences.setAvailableUpdateVersion(updateInfo.latestVersion)
            appPreferences.setAvailableUpdateChangelog(updateInfo.changelog)
            appPreferences.setAvailableUpdateUrl(updateInfo.webUrl)

            showUpdateNotification(updateInfo)
            Result.success()
        } catch (e: Exception) {
            Timber.d("Update check failed: $e")
            Result.retry()
        }
    }

    private fun showUpdateNotification(updateInfo: UpdateChecker.UpdateInfo) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = applicationContext.getString(R.string.update_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Open store URL on tap
        val intent = Intent(Intent.ACTION_VIEW, updateInfo.webUrl.toUri())
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val changelogPreview = updateInfo.changelog
            .lines()
            .take(3)
            .joinToString("\n")
            .take(200)

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(
                applicationContext.getString(R.string.update_available_title, updateInfo.latestVersion)
            )
            .setContentText(applicationContext.getString(R.string.update_available_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(changelogPreview.ifBlank {
                        applicationContext.getString(R.string.update_available_text)
                    })
            )
            .setSmallIcon(R.drawable.ic_shield_on)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
