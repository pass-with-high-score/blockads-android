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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.NumberFormat
import java.util.Locale

class NotificationHelper(
    private val context: Context,
    private val appPrefs: AppPreferences
) {
    companion object {
        const val MILESTONE_CHANNEL_ID = "blockads_milestone_channel"
        private const val MILESTONE_NOTIFICATION_ID = 3001
        val MILESTONES = longArrayOf(100, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000)
    }

    private val milestoneMutex = Mutex()

    /**
     * Returns the next milestone threshold above [lastMilestone], or null if all milestones
     * have been reached. Used to avoid DB queries until the in-memory count crosses a threshold.
     */
    fun nextMilestoneThreshold(lastMilestone: Long): Long? {
        return MILESTONES.firstOrNull { it > lastMilestone }
    }

    /**
     * Thread-safe milestone check. Uses a Mutex to prevent concurrent calls from producing
     * duplicate notifications. Caller passes the cached in-memory total so no DB query is needed.
     */
    suspend fun checkAndNotifyMilestone(totalBlocked: Long) {
        milestoneMutex.withLock {
            val enabled = appPrefs.milestoneNotificationsEnabled.first()
            if (!enabled) return

            val lastMilestone = appPrefs.lastMilestoneBlocked.first()

            // Advance to the highest milestone <= totalBlocked in one step to avoid spam
            val reachedMilestone = MILESTONES.filter { it <= totalBlocked }.maxOrNull()
            if (reachedMilestone != null && reachedMilestone > lastMilestone) {
                appPrefs.setLastMilestoneBlocked(reachedMilestone)
                showMilestoneNotification(reachedMilestone)
            }
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
