package app.pwhs.blockads

import app.pwhs.blockads.data.datastore.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownDurationValidationTest {

    @Test
    fun testAllowedLockdownDurationsContainsStandardPresets() {
        val expectedPresets = setOf(
            60000L,       // 1m
            300000L,      // 5m
            600000L,      // 10m
            1800000L,     // 30m
            3600000L,     // 1h
            21600000L,    // 6h
            43200000L,    // 12h
            86400000L     // 24h
        )
        assertEquals(expectedPresets, AppPreferences.ALLOWED_LOCKDOWN_DURATIONS)
    }

    @Test
    fun testInvalidDurationsRejected() {
        val invalidDurations = listOf(0L, -1L, -300000L, 100L, 999999L)
        for (duration in invalidDurations) {
            assertFalse(
                "Duration $duration should not be in ALLOWED_LOCKDOWN_DURATIONS",
                duration in AppPreferences.ALLOWED_LOCKDOWN_DURATIONS
            )
        }
    }

    @Test
    fun testValidDurationsAccepted() {
        for (duration in AppPreferences.ALLOWED_LOCKDOWN_DURATIONS) {
            assertTrue(
                "Duration $duration should be in ALLOWED_LOCKDOWN_DURATIONS",
                duration in AppPreferences.ALLOWED_LOCKDOWN_DURATIONS
            )
        }
    }

    @Test
    fun testDefaultLockdownDurationIsFiveMinutes() {
        assertEquals(300000L, AppPreferences.DEFAULT_LOCKDOWN_DURATION)
        assertTrue(AppPreferences.DEFAULT_LOCKDOWN_DURATION in AppPreferences.ALLOWED_LOCKDOWN_DURATIONS)
    }
}
