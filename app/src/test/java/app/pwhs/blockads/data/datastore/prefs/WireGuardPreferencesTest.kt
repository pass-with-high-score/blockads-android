package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WireGuardPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newPreferences(): WireGuardPreferences {
        val file = File(tempFolder.newFolder(), "wireguard.preferences_pb")
        return WireGuardPreferences(PreferenceDataStoreFactory.create { file })
    }

    @Test
    fun `allowAppBypass stays opt-in so WireGuard mode starts non-bypassable`() = runTest {
        assertFalse(newPreferences().allowAppBypass.first())
    }

    @Test
    fun `allowAppBypass keeps an explicit opt-in`() = runTest {
        val prefs = newPreferences()
        prefs.setAllowAppBypass(true)
        assertTrue(prefs.allowAppBypass.first())
    }

    @Test
    fun `excludeLan stays opt-in`() = runTest {
        assertFalse(newPreferences().excludeLan.first())
    }
}
