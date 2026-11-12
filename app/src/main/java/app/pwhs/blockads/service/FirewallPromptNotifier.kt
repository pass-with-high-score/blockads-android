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

/** Posts and cancels the "<App> wants to access the internet" notification. */
class FirewallPromptNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "firewall_prompt_channel"
        private const val NOTIFICATION_ID_BASE = 0x4657_0000
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun show(prompt: PendingFirewallPrompt) {
        ensureChannel()
        val pkg = prompt.packageName
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.firewall_prompt_notification_title, prompt.appLabel))
            .setContentText(context.getString(R.string.firewall_prompt_notification_text, pkg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(pkg))
            .addAction(0, context.getString(R.string.firewall_prompt_action_allow), actionIntent(pkg, FirewallPromptReceiver.ACTION_ALLOW))
            .addAction(0, context.getString(R.string.firewall_prompt_action_block), actionIntent(pkg, FirewallPromptReceiver.ACTION_BLOCK))
            .build()
        runCatching { notificationManager.notify(notificationId(pkg), notification) }
    }

    fun cancel(packageName: String) {
        notificationManager.cancel(notificationId(packageName))
    }

    private fun notificationId(packageName: String) = NOTIFICATION_ID_BASE + (packageName.hashCode() and 0xFFFF)

    private fun actionIntent(packageName: String, action: String): PendingIntent {
        val intent = Intent(context, FirewallPromptReceiver::class.java).apply {
            this.action = action
            putExtra(FirewallPromptReceiver.EXTRA_PACKAGE_NAME, packageName)
        }
        val requestCode = (action + packageName).hashCode()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(packageName: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, packageName.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.firewall_prompt_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.firewall_prompt_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
