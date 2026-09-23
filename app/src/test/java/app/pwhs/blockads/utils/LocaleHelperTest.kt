package app.pwhs.blockads.utils

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.datastore.AppPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class LocaleHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val savedLocale = Locale.getDefault()

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    @Config(sdk = [33])
    @Test
    fun `setLocale applies the per-app locale on API 33`() {
        val manager = context.getSystemService(LocaleManager::class.java)

        LocaleHelper.setLocale(context, AppPreferences.LANGUAGE_PT_BR)
        assertEquals(LocaleList.forLanguageTags("pt-BR"), manager.applicationLocales)

        LocaleHelper.setLocale(context, AppPreferences.LANGUAGE_SYSTEM)
        assertEquals(LocaleList.getEmptyLocaleList(), manager.applicationLocales)
    }

    @Config(sdk = [33])
    @Test
    fun `wrapContext is a no-op on API 33`() {
        assertSame(context, LocaleHelper.wrapContext(context, AppPreferences.LANGUAGE_VI))
    }

    @Config(sdk = [28])
    @Test
    fun `setLocale leaves pre-33 devices to the activity recreate`() {
        LocaleHelper.setLocale(context, AppPreferences.LANGUAGE_VI)
        assertEquals(savedLocale, Locale.getDefault())
    }

    @Config(sdk = [28])
    @Test
    fun `wrapContext keeps the system language untouched before API 33`() {
        assertSame(context, LocaleHelper.wrapContext(context, AppPreferences.LANGUAGE_SYSTEM))
    }

    @Config(sdk = [28])
    @Test
    fun `wrapContext applies the chosen language before API 33`() {
        val wrapped = LocaleHelper.wrapContext(context, AppPreferences.LANGUAGE_JA)

        assertEquals(Locale.JAPANESE, wrapped.resources.configuration.locales[0])
        assertEquals(Locale.JAPANESE, Locale.getDefault())
    }
}
