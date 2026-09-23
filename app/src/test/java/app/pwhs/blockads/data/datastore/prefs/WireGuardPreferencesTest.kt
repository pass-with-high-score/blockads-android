package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.data.entities.WireGuardProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun profile(id: String, vararg allowed: String) = WireGuardProfile(
        id = id,
        name = id,
        config = WireGuardConfig(
            WireGuardInterface(privateKey = "k", address = listOf("10.0.0.2/32")),
            listOf(WireGuardPeer(publicKey = "p", allowedIPs = allowed.toList())),
        ),
    )

    @Test
    fun `deleting the active profile falls back past broken profiles`() = runTest {
        val prefs = newPreferences()
        prefs.addOrUpdateWgProfile(profile("good", "10.0.0.0/24"), makeActive = true)
        prefs.addOrUpdateWgProfile(profile("broken"))
        prefs.addOrUpdateWgProfile(profile("other", "0.0.0.0/0"))
        prefs.removeWgProfile("good")
        assertEquals("other", prefs.getActiveWgProfileSnapshot()?.id)
    }

    @Test
    fun `no active profile when only broken ones remain`() = runTest {
        val prefs = newPreferences()
        prefs.addOrUpdateWgProfile(profile("good", "10.0.0.0/24"), makeActive = true)
        prefs.addOrUpdateWgProfile(profile("broken", "fe80::1%wlan0/64"))
        prefs.removeWgProfile("good")
        assertNull(prefs.getActiveWgProfileSnapshot())
    }
}
