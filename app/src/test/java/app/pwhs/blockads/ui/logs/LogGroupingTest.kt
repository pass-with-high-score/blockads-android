package app.pwhs.blockads.ui.logs

import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.ui.logs.data.LogGrouping
import app.pwhs.blockads.ui.logs.data.groupLogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogGroupingTest {

    private fun log(
        id: Long,
        domain: String,
        isBlocked: Boolean,
        appName: String = "",
        packageName: String = "",
        timestamp: Long = id * 1000L
    ) = DnsLogEntry(
        id = id,
        domain = domain,
        isBlocked = isBlocked,
        appName = appName,
        packageName = packageName,
        timestamp = timestamp
    )

    @Test
    fun `groupLogs returns empty for NONE or empty input`() {
        val sample = listOf(log(1, "ads.example.com", true, "Chrome"))
        assertTrue(groupLogs(sample, LogGrouping.NONE, "Unknown").isEmpty())
        assertTrue(groupLogs(emptyList(), LogGrouping.BY_DOMAIN, "Unknown").isEmpty())
    }

    @Test
    fun `groupLogs BY_DOMAIN groups case-insensitively and sorts by latest timestamp`() {
        val logs = listOf(
            log(1, "Ads.Example.com", true, appName = "Chrome", timestamp = 1000L),
            log(2, "api.google.com", false, appName = "Maps", timestamp = 5000L),
            log(3, "ads.example.com", false, appName = "YouTube", timestamp = 9000L)
        )

        val groups = groupLogs(logs, LogGrouping.BY_DOMAIN, "Unknown")

        assertEquals(2, groups.size)
        assertEquals("ads.example.com", groups[0].key)
        assertEquals(2, groups[0].totalCount)
        assertEquals(1, groups[0].blockedCount)
        assertEquals(9000L, groups[0].latestTimestamp)
        assertEquals("Chrome +1", groups[0].subtitle)

        assertEquals("api.google.com", groups[1].key)
        assertEquals(1, groups[1].totalCount)
        assertEquals(0, groups[1].blockedCount)
    }

    @Test
    fun `groupLogs BY_APP groups blank app names under unknown label and retains package name`() {
        val logs = listOf(
            log(1, "a.com", true, appName = "Chrome", packageName = "com.android.chrome", timestamp = 4000L),
            log(2, "b.com", true, appName = "Chrome", packageName = "com.android.chrome", timestamp = 6000L),
            log(3, "c.com", false, appName = "", timestamp = 2000L)
        )

        val groups = groupLogs(logs, LogGrouping.BY_APP, "Unknown")

        assertEquals(2, groups.size)
        assertEquals("Chrome", groups[0].title)
        assertEquals("com.android.chrome", groups[0].packageName)
        assertEquals(2, groups[0].totalCount)
        assertEquals(2, groups[0].blockedCount)

        assertEquals("Unknown", groups[1].title)
        assertEquals(1, groups[1].totalCount)
        assertEquals(0, groups[1].blockedCount)
    }
}
