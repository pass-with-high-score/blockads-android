package app.pwhs.blockads.data.datastore.prefs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppearancePreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val prefs by lazy { AppearancePreferences(tempFolder.newPreferencesDataStore()) }

    @Test
    fun `defaults follow the system with green accent and labels shown`() = runTest {
        assertEquals(AppearancePreferences.THEME_SYSTEM, prefs.themeMode.first())
        assertEquals(AppearancePreferences.LANGUAGE_SYSTEM, prefs.appLanguage.first())
        assertEquals(AppearancePreferences.ACCENT_GREEN, prefs.accentColor.first())
        assertTrue(prefs.showBottomNavLabels.first())
    }

    @Test
    fun `round trips`() = runTest {
        prefs.setThemeMode(AppearancePreferences.THEME_DARK)
        prefs.setAppLanguage(AppearancePreferences.LANGUAGE_PT_BR)
        prefs.setAccentColor(AppearancePreferences.ACCENT_DYNAMIC)
        prefs.setShowBottomNavLabels(false)

        assertEquals(AppearancePreferences.THEME_DARK, prefs.themeMode.first())
        assertEquals("pt-BR", prefs.appLanguage.first())
        assertEquals(AppearancePreferences.ACCENT_DYNAMIC, prefs.accentColor.first())
        assertFalse(prefs.showBottomNavLabels.first())
    }

    @Test
    fun `unknown values are stored verbatim for the UI to interpret`() = runTest {
        prefs.setThemeMode("sepia")
        assertEquals("sepia", prefs.themeMode.first())
    }
}
