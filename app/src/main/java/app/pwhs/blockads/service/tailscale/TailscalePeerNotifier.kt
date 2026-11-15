package app.pwhs.blockads.service.tailscale

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import org.json.JSONObject

class TailscalePeerNotifier(private val context: Context) {

    private val knownOnlinePeerIds = mutableSetOf<Long>()
    private var isInitialized = false

    companion object {
        const val CHANNEL_ID = "blockads_tailscale_channel"
    }

    @Synchronized
    fun checkAndNotifyJson(statusJson: String) {
        if (statusJson.isEmpty() || statusJson == "{}") return
        try {
            val obj = JSONObject(statusJson)
            val selfNodeObj = obj.optJSONObject("self_node")
            val selfNodeId = selfNodeObj?.optLong("id") ?: 0L

            val peersArr = obj.optJSONArray("peers") ?: return
            val currentOnlinePeers = mutableListOf<Pair<Long, String>>()

            for (i in 0 until peersArr.length()) {
                val p = peersArr.getJSONObject(i)
                val id = p.optLong("id")
                val isSelf = p.optBoolean("is_self") || (id != 0L && id == selfNodeId)
                val isOnline = p.optBoolean("online")
                val name = p.optString("name").trim().trimEnd('.')

                if (!isSelf && isOnline && id != 0L) {
                    currentOnlinePeers.add(id to name)
                }
            }

            val currentOnlineIds = currentOnlinePeers.map { it.first }.toSet()

            if (isInitialized) {
                for ((id, name) in currentOnlinePeers) {
                    if (!knownOnlinePeerIds.contains(id)) {
                        showNotification(name)
                    }
                }
            } else {
                isInitialized = true
            }

            knownOnlinePeerIds.clear()
            knownOnlinePeerIds.addAll(currentOnlineIds)
        } catch (_: Exception) {
        }
    }

    private fun showNotification(peerName: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.tailscale_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.tailscale_notification_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            peerName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.tailscale_peer_connected_title))
            .setContentText(context.getString(R.string.tailscale_peer_connected_text, peerName))
            .setSmallIcon(R.drawable.ic_shield_on)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }
}
