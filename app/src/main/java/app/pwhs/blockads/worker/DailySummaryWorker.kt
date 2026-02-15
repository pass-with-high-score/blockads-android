package app.pwhs.blockads.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsLogDao
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar

class DailySummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val dnsLogDao: DnsLogDao by inject()
    private val appPreferences: AppPreferences by inject()

    companion object {
        const val WORK_NAME = "daily_summary_work"
        const val CHANNEL_ID = "blockads_daily_summary_channel"
        private const val NOTIFICATION_ID = 2001
    }

    override suspend fun doWork(): Result {
        return try {
            val enabled = appPreferences.dailySummaryEnabled.first()
            if (!enabled) return Result.success()

            val startOfDay = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val blockedToday = dnsLogDao.getBlockedCountSinceSync(startOfDay)

            if (blockedToday > 0) {
                showDailySummaryNotification(blockedToday)
            }

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun showDailySummaryNotification(blockedCount: Int) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.daily_summary_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    applicationContext.getString(R.string.daily_summary_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.daily_summary_title))
            .setContentText(
                applicationContext.getString(R.string.daily_summary_text, blockedCount)
            )
            .setSmallIcon(R.drawable.ic_shield_on)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
