package app.pwhs.blockads.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.R
import app.pwhs.blockads.service.vpn.VpnNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class VpnNotificationManagerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = VpnNotificationManager(app)
    private val shadowNm = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private fun build(
        isConnecting: Boolean = false,
        isReconnecting: Boolean = false,
        isStopping: Boolean = false,
        isRunning: Boolean = false,
        phase: String = "",
        retryCount: Int = 0,
        startTime: Long = System.currentTimeMillis(),
        blocked: Int = 0,
        networkLost: Boolean = false,
    ) = manager.buildForegroundNotification(
        state = VpnState.RUNNING, isConnecting = isConnecting, isReconnecting = isReconnecting,
        isStopping = isStopping, isRunning = isRunning, connectingPhase = phase, retryCount = retryCount,
        maxRetries = 5, vpnStartTime = startTime, todayBlockedCount = blocked, isPhysicalNetworkLost = networkLost,
    )

    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private val Notification.actionTitles get() = actions.map { it.title.toString() }
    private fun s(id: Int, vararg args: Any) = app.getString(id, *args)

    @Test
    fun `channels are created with the right importance`() {
        manager.createChannels()
        val channels = shadowNm.notificationChannels.associateBy { (it as android.app.NotificationChannel).id }
        assertEquals(NotificationManager.IMPORTANCE_LOW, (channels.getValue(VpnNotificationManager.CHANNEL_ID) as android.app.NotificationChannel).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, (channels.getValue(VpnNotificationManager.ALERT_CHANNEL_ID) as android.app.NotificationChannel).importance)
    }

    @Test
    fun `running shows today's blocks, uptime and pause-stop actions`() {
        val n = build(isRunning = true, blocked = 12, startTime = System.currentTimeMillis() - 3_725_000)
        assertEquals(s(R.string.vpn_notification_title), n.title)
        assertEquals(s(R.string.vpn_notification_stats_today, 12, "1:02:05"), n.text)
        assertEquals(listOf(s(R.string.vpn_notification_action_pause), s(R.string.vpn_notification_action_stop)), n.actionTitles)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `short uptime drops the hour field`() {
        val n = build(isRunning = true, blocked = 1, startTime = System.currentTimeMillis() - 65_000)
        assertEquals(s(R.string.vpn_notification_stats_today, 1, "1:05"), n.text)
    }

    @Test
    fun `stopping wins over everything`() {
        val n = build(isStopping = true, networkLost = true, isReconnecting = true, retryCount = 2)
        assertEquals(s(R.string.vpn_notification_stopping), n.title)
        assertEquals(s(R.string.vpn_notification_stopping_text), n.text)
    }

    @Test
    fun `lost physical network offers only stop`() {
        val n = build(networkLost = true, isReconnecting = true)
        assertEquals(s(R.string.vpn_notification_no_internet_title), n.title)
        assertEquals(s(R.string.vpn_notification_no_internet_text), n.text)
        assertEquals(listOf(s(R.string.vpn_notification_action_stop)), n.actionTitles)
    }

    @Test
    fun `reconnecting shows the phase when there is one and offers retry`() {
        assertEquals("Waiting 3s", build(isReconnecting = true, phase = "Waiting 3s").text)
        val n = build(isReconnecting = true)
        assertEquals(s(R.string.vpn_notification_reconnecting), n.title)
        assertEquals(s(R.string.vpn_notification_reconnecting_text), n.text)
        assertEquals(listOf(s(R.string.vpn_notification_action_retry), s(R.string.vpn_notification_action_stop)), n.actionTitles)
    }

    @Test
    fun `retrying shows the attempt count`() {
        val n = build(retryCount = 2)
        assertEquals(s(R.string.vpn_notification_retrying), n.title)
        assertEquals(s(R.string.vpn_notification_retry_text, 2, 5), n.text)
    }

    @Test
    fun `connecting shows its phase, idle shows the default text`() {
        val connecting = build(isConnecting = true, phase = "Loading filters")
        assertEquals(s(R.string.status_connecting), connecting.title)
        assertEquals("Loading filters", connecting.text)
        assertEquals(s(R.string.vpn_notification_text), build().text)
    }

    @Test
    fun `paused, stopped and revoked notifications are posted`() {
        manager.showPausedNotification()
        assertEquals(s(R.string.vpn_paused_title), shadowNm.getNotification(VpnNotificationManager.NOTIFICATION_ID).title)
        manager.showStoppedNotification()
        val stopped = shadowNm.getNotification(VpnNotificationManager.NOTIFICATION_ID)
        assertEquals(s(R.string.vpn_stopped_title), stopped.title)
        assertEquals(listOf(s(R.string.vpn_stopped_action_enable)), stopped.actionTitles)

        manager.showRevokedNotification()
        val revoked = shadowNm.getNotification(VpnNotificationManager.REVOKED_NOTIFICATION_ID)
        assertEquals(s(R.string.vpn_revoked_title), revoked.title)
        assertEquals(VpnNotificationManager.ALERT_CHANNEL_ID, revoked.channelId)

        manager.updateNotification(build(isStopping = true))
        assertEquals(s(R.string.vpn_notification_stopping), shadowNm.getNotification(VpnNotificationManager.NOTIFICATION_ID).title)
    }
}
