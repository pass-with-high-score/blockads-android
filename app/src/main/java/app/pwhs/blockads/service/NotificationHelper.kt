package app.pwhs.blockads.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.AppPreferences
import app.pwhs.blockads.data.DnsLogDao
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.util.Locale

class NotificationHelper(
    private val context: Context,
    private val appPrefs: AppPreferences,
    private val dnsLogDao: DnsLogDao
) {
    companion object {
        const val MILESTONE_CHANNEL_ID = "blockads_milestone_channel"
        private const val MILESTONE_NOTIFICATION_ID = 3001
        val MILESTONES = longArrayOf(100, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000)
    }

    suspend fun checkAndNotifyMilestone(totalBlocked: Long) {
        val enabled = appPrefs.milestoneNotificationsEnabled.first()
        if (!enabled) return
        val lastMilestone = appPrefs.lastMilestoneBlocked.first()

        // Determine the highest milestone that has been reached given the current total.
        val reachedMilestone = MILESTONES.filter { it <= totalBlocked }.maxOrNull()
        if (reachedMilestone != null && reachedMilestone > lastMilestone) {
            appPrefs.setLastMilestoneBlocked(reachedMilestone)
            showMilestoneNotification(reachedMilestone)
        }
    }

    private fun showMilestoneNotification(milestone: Long) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MILESTONE_CHANNEL_ID,
                context.getString(R.string.milestone_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.milestone_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedCount = NumberFormat.getNumberInstance(Locale.getDefault()).format(milestone)
        val notification = NotificationCompat.Builder(context, MILESTONE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.milestone_title))
            .setContentText(context.getString(R.string.milestone_text, formattedCount))
            .setSmallIcon(R.drawable.ic_shield_on)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(MILESTONE_NOTIFICATION_ID, notification)
    }
}
