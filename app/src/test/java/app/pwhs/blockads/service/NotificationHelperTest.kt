package app.pwhs.blockads.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notifications = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
    private var lastMilestone = 0L
    private var enabled = true
    private val prefs: AppPreferences = mockk {
        every { milestoneNotificationsEnabled } answers { flowOf(enabled) }
        every { lastMilestoneBlocked } answers { flowOf(lastMilestone) }
        coEvery { setLastMilestoneBlocked(any()) } answers { lastMilestone = firstArg() }
    }
    private val helper = NotificationHelper(app, prefs)

    @Test
    fun `next threshold walks the milestone ladder`() {
        assertEquals(1_000L, helper.nextMilestoneThreshold(0))
        assertEquals(10_000L, helper.nextMilestoneThreshold(1_000))
        assertEquals(1_000_000L, helper.nextMilestoneThreshold(999_999))
        assertNull(helper.nextMilestoneThreshold(1_000_000))
    }

    @Test
    fun `crossing milestones notifies once at the highest reached`() = runTest {
        helper.checkAndNotifyMilestone(12_345)
        assertEquals(10_000L, lastMilestone)
        assertEquals(1, notifications.allNotifications.size)
        assertTrue(notifications.notificationChannels.any { (it as android.app.NotificationChannel).id == NotificationHelper.MILESTONE_CHANNEL_ID })

        helper.checkAndNotifyMilestone(13_000)
        assertEquals(1, notifications.allNotifications.size)
    }

    @Test
    fun `below the first milestone or disabled does nothing`() = runTest {
        helper.checkAndNotifyMilestone(999)
        enabled = false
        helper.checkAndNotifyMilestone(2_000_000)
        assertEquals(0, notifications.allNotifications.size)
        coVerify(exactly = 0) { prefs.setLastMilestoneBlocked(any()) }
    }
}
