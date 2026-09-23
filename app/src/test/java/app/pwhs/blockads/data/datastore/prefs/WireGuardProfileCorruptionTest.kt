package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.data.entities.WireGuardProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WireGuardProfileCorruptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(): DataStore<Preferences> {
        val file = File(tempFolder.newFolder(), "wireguard.preferences_pb")
        return PreferenceDataStoreFactory.create { file }
    }

    private fun profile(id: String, key: String) = WireGuardProfile(
        id = id,
        name = id,
        config = WireGuardConfig(
            interfaceConfig = WireGuardInterface(privateKey = key, address = listOf("10.0.0.2/32")),
            peers = listOf(WireGuardPeer(publicKey = "pub-$id", endpoint = "vpn.example:51820")),
        ),
    )

    @Ignore("known bug: saving over an unparseable profile list overwrites every stored profile")
    @Test
    fun `saving a profile never destroys stored profiles that fail to parse`() = runTest {
        val store = newStore()
        val prefs = WireGuardPreferences(store)
        prefs.addOrUpdateWgProfile(profile("home", "home-private-key"), makeActive = true)
        // A field type change, as a schema migration or partial write could produce.
        store.edit { p ->
            p[WireGuardPreferences.KEY_WG_PROFILES_JSON] =
                p[WireGuardPreferences.KEY_WG_PROFILES_JSON]!!.replace("\"peers\":[", "\"peers\":{\"x\":[") + "}"
        }

        runCatching { prefs.addOrUpdateWgProfile(profile("work", "work-private-key")) }

        val survivors = store.data.first().asMap().values.map { it.toString() }
        assertTrue(
            "home profile key was overwritten; stored values: $survivors",
            survivors.any { "home-private-key" in it },
        )
    }
}
