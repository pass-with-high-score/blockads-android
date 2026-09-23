package app.pwhs.blockads.data.datastore.prefs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilterPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val prefs by lazy { FilterPreferences(tempFolder.newPreferencesDataStore()) }

    @Test
    fun `defaults`() = runTest {
        assertEquals(FilterPreferences.DEFAULT_FILTER_URL, prefs.filterUrl.first())
        assertTrue(prefs.autoUpdateEnabled.first())
        assertEquals(FilterPreferences.UPDATE_FREQUENCY_24H, prefs.autoUpdateFrequency.first())
        assertTrue(prefs.autoUpdateWifiOnly.first())
        assertEquals(FilterPreferences.NOTIFICATION_SILENT, prefs.autoUpdateNotification.first())
        assertEquals(FilterPreferences.PROTECTION_STANDARD, prefs.protectionLevel.first())
        assertFalse(prefs.safeSearchEnabled.first())
        assertFalse(prefs.youtubeRestrictedMode.first())
    }

    @Test
    fun `round trips`() = runTest {
        prefs.setFilterUrl("https://lists.example/hosts")
        prefs.setAutoUpdateEnabled(false)
        prefs.setAutoUpdateFrequency(FilterPreferences.UPDATE_FREQUENCY_MANUAL)
        prefs.setAutoUpdateWifiOnly(false)
        prefs.setAutoUpdateNotification(FilterPreferences.NOTIFICATION_NONE)
        prefs.setProtectionLevel(FilterPreferences.PROTECTION_STRICT)
        prefs.setSafeSearchEnabled(true)
        prefs.setYoutubeRestrictedMode(true)

        assertEquals("https://lists.example/hosts", prefs.filterUrl.first())
        assertFalse(prefs.autoUpdateEnabled.first())
        assertEquals(FilterPreferences.UPDATE_FREQUENCY_MANUAL, prefs.autoUpdateFrequency.first())
        assertFalse(prefs.autoUpdateWifiOnly.first())
        assertEquals(FilterPreferences.NOTIFICATION_NONE, prefs.autoUpdateNotification.first())
        assertEquals(FilterPreferences.PROTECTION_STRICT, prefs.protectionLevel.first())
        assertTrue(prefs.safeSearchEnabled.first())
        assertTrue(prefs.youtubeRestrictedMode.first())
    }
}
