package app.pwhs.blockads.service.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.VpnState
import java.util.Locale

class VpnNotificationManager(private val context: Context) {

    companion object {
        const val NOTIFICATION_ID = 1
        const val REVOKED_NOTIFICATION_ID = 2
        const val CHANNEL_ID = "blockads_vpn_channel"
        const val ALERT_CHANNEL_ID = "blockads_vpn_alert_channel"
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val normalChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.vpn_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.vpn_alert_channel_description)
            }

            notificationManager?.apply {
                createNotificationChannel(normalChannel)
                createNotificationChannel(alertChannel)
            }
        }
    }

    fun buildForegroundNotification(
        state: VpnState,
        isConnecting: Boolean,
        isReconnecting: Boolean,
        isStopping: Boolean,
        isRunning: Boolean,
        connectingPhase: String,
        retryCount: Int,
        maxRetries: Int,
        vpnStartTime: Long,
        todayBlockedCount: Int
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_PAUSE_1H
        }
        val pausePendingIntent = PendingIntent.getService(
            context, 4, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val title = when {
            isStopping -> context.getString(R.string.vpn_notification_stopping)
            isReconnecting && connectingPhase.isNotEmpty() -> context.getString(R.string.vpn_notification_reconnecting)
            isReconnecting -> context.getString(R.string.vpn_notification_reconnecting)
            retryCount > 0 -> context.getString(R.string.vpn_notification_retrying)
            isConnecting && connectingPhase.isNotEmpty() -> context.getString(R.string.status_connecting)
            else -> context.getString(R.string.vpn_notification_title)
        }

        val text = when {
            isStopping -> context.getString(R.string.vpn_notification_stopping_text)
            isReconnecting && connectingPhase.isNotEmpty() -> connectingPhase
            isReconnecting -> context.getString(R.string.vpn_notification_reconnecting_text)
            retryCount > 0 -> context.getString(
                R.string.vpn_notification_retry_text,
                retryCount,
                maxRetries
            )
            isConnecting && connectingPhase.isNotEmpty() -> connectingPhase
            isRunning -> {
                val uptimeStr = formatUptime(System.currentTimeMillis() - vpnStartTime)
                context.getString(R.string.vpn_notification_stats_today, todayBlockedCount, uptimeStr)
            }
            else -> context.getString(R.string.vpn_notification_text)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, context.getString(R.string.vpn_notification_action_pause), pausePendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null, context.getString(R.string.vpn_notification_action_stop), stopPendingIntent
                ).build()
            )
            .build()
    }

    fun showPausedNotification() {
        createChannels()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        val startPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context, 3, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context, 3, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle(context.getString(R.string.vpn_paused_title))
            .setContentText(context.getString(R.string.vpn_paused_text))
            .setSmallIcon(R.drawable.ic_shield_off)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, context.getString(R.string.vpn_stopped_action_enable), startPendingIntent
                ).build()
            )
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    fun showStoppedNotification() {
        createChannels()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        val startPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context, 3, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context, 3, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle(context.getString(R.string.vpn_stopped_title))
            .setContentText(context.getString(R.string.vpn_stopped_text))
            .setSmallIcon(R.drawable.ic_shield_off)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, context.getString(R.string.vpn_stopped_action_enable), startPendingIntent
                ).build()
            )
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    fun showRevokedNotification() {
        createChannels()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle(context.getString(R.string.vpn_revoked_title))
            .setContentText(context.getString(R.string.vpn_revoked_text))
            .setSmallIcon(R.drawable.ic_error)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager?.notify(REVOKED_NOTIFICATION_ID, notification)
    }

    fun updateNotification(notification: Notification) {
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun formatUptime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}
