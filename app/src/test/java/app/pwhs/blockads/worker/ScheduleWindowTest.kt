package app.pwhs.blockads.worker

import app.pwhs.blockads.data.entities.FirewallRule
import app.pwhs.blockads.data.entities.ProfileSchedule
import app.pwhs.blockads.service.FirewallManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** Firewall rules and profile schedules share one window model; these pin where they agree and where they don't. */
class ScheduleWindowTest {

    private val monday = 1
    private val tuesday = 2

    private fun rule(sh: Int, sm: Int, eh: Int, em: Int) = FirewallRule(
        packageName = "p", scheduleEnabled = true,
        scheduleStartHour = sh, scheduleStartMinute = sm, scheduleEndHour = eh, scheduleEndMinute = em
    )

    private fun schedule(sh: Int, sm: Int, eh: Int, em: Int, days: String = "1,2,3,4,5,6,7") =
        ProfileSchedule(profileId = 7, startHour = sh, startMinute = sm, endHour = eh, endMinute = em, daysOfWeek = days)

    private fun firewallActive(r: FirewallRule, h: Int, m: Int) = FirewallManager.isWithinSchedule(r, h * 60 + m)

    private fun profileActive(s: ProfileSchedule, day: Int, h: Int, m: Int) =
        ProfileScheduleWorker.activeScheduleAt(listOf(s), day, h * 60 + m) != null

    @Test
    fun `same-day window covers its start and interior on both`() {
        assertTrue(firewallActive(rule(8, 0, 17, 0), 8, 0))
        assertTrue(firewallActive(rule(8, 0, 17, 0), 16, 59))
        assertTrue(profileActive(schedule(8, 0, 17, 0), monday, 8, 0))
        assertTrue(profileActive(schedule(8, 0, 17, 0), monday, 16, 59))
        assertFalse(profileActive(schedule(8, 0, 17, 0), monday, 7, 59))
    }

    @Test
    fun `overnight window wraps midnight on both`() {
        assertTrue(firewallActive(rule(22, 0, 6, 0), 23, 30))
        assertTrue(firewallActive(rule(22, 0, 6, 0), 0, 0))
        assertFalse(firewallActive(rule(22, 0, 6, 0), 12, 0))
        assertTrue(profileActive(schedule(22, 0, 6, 0), monday, 23, 30))
        assertFalse(profileActive(schedule(22, 0, 6, 0), monday, 12, 0))
    }

    @Test
    fun `first matching schedule wins`() {
        val a = schedule(8, 0, 12, 0).copy(id = 1)
        val b = schedule(9, 0, 17, 0).copy(id = 2)
        assertEquals(1L, ProfileScheduleWorker.activeScheduleAt(listOf(a, b), monday, 10 * 60)?.id)
        assertNull(ProfileScheduleWorker.activeScheduleAt(listOf(a, b), monday, 18 * 60))
    }

    @Ignore("firewall treats the end minute as inclusive, profiles as exclusive; decide which is intended")
    @Test
    fun `firewall and profile schedules agree at the end minute`() {
        for ((window, at) in listOf((8 to 17) to 17, (22 to 6) to 6)) {
            val (start, end) = window
            assertEquals(
                "firewall and profile disagree for $start:00-$end:00 at $at:00",
                profileActive(schedule(start, 0, end, 0), monday, at, 0),
                firewallActive(rule(start, 0, end, 0), at, 0)
            )
        }
    }

    @Ignore("start==end means an empty window for profiles and one minute for the firewall; decide the semantics")
    @Test
    fun `start equal to end means the same thing for both`() {
        assertEquals(
            profileActive(schedule(9, 0, 9, 0), monday, 9, 0),
            firewallActive(rule(9, 0, 9, 0), 9, 0)
        )
    }

    @Ignore("overnight profile windows check the weekday at evaluation time; decide whether the start day owns the window")
    @Test
    fun `overnight profile window started on a selected day continues past midnight`() {
        val mondayNight = schedule(22, 0, 6, 0, days = "1")
        assertNotNull(
            "Monday 22:00-06:00 window is not active at Tuesday 01:00",
            ProfileScheduleWorker.activeScheduleAt(listOf(mondayNight), tuesday, 60)
        )
    }
}
