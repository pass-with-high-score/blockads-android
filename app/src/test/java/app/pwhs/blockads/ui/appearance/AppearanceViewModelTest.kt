package app.pwhs.blockads.ui.appearance

import android.app.Application
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.keepHot
import app.pwhs.blockads.utils.LocaleHelper
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AppearanceViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val theme = MutableStateFlow(AppPreferences.THEME_SYSTEM)
    private val language = MutableStateFlow(AppPreferences.LANGUAGE_SYSTEM)
    private val accent = MutableStateFlow(AppPreferences.ACCENT_GREEN)
    private val labels = MutableStateFlow(true)
    private val appPrefs: AppPreferences = mockk(relaxed = true) {
        every { themeMode } returns theme
        every { appLanguage } returns language
        every { accentColor } returns accent
        every { showBottomNavLabels } returns labels
    }
    private val vm by lazy { AppearanceViewModel(appPrefs, mockk<Application>(relaxed = true)) }

    @Before
    fun setUp() {
        mockkObject(LocaleHelper)
        every { LocaleHelper.setLocale(any(), any()) } just Runs
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `state mirrors appearance preferences`() = runTest {
        keepHot(vm.themeMode, vm.appLanguage, vm.accentColor, vm.showBottomNavLabels)
        theme.value = AppPreferences.THEME_DARK
        language.value = AppPreferences.LANGUAGE_VI
        accent.value = AppPreferences.ACCENT_TEAL
        labels.value = false
        assertEquals(AppPreferences.THEME_DARK, vm.themeMode.value)
        assertEquals(AppPreferences.LANGUAGE_VI, vm.appLanguage.value)
        assertEquals(AppPreferences.ACCENT_TEAL, vm.accentColor.value)
        assertFalse(vm.showBottomNavLabels.value)
    }

    @Test
    fun `theme, accent and label choices persist`() {
        vm.setThemeMode(AppPreferences.THEME_LIGHT)
        vm.setAccentColor(AppPreferences.ACCENT_DYNAMIC)
        vm.setShowBottomNavLabels(false)
        coVerify {
            appPrefs.setThemeMode(AppPreferences.THEME_LIGHT)
            appPrefs.setAccentColor(AppPreferences.ACCENT_DYNAMIC)
            appPrefs.setShowBottomNavLabels(false)
        }
    }

    @Test
    fun `a language change is saved before the locale is applied`() {
        vm.setAppLanguage(AppPreferences.LANGUAGE_JA)
        coVerifyOrder {
            appPrefs.setAppLanguage(AppPreferences.LANGUAGE_JA)
            LocaleHelper.setLocale(any(), AppPreferences.LANGUAGE_JA)
        }
        verify(exactly = 1) { LocaleHelper.setLocale(any(), any()) }
    }
}
