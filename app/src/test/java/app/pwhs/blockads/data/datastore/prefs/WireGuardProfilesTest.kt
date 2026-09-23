package app.pwhs.blockads.data.datastore.prefs

import androidx.datastore.preferences.core.edit
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.data.entities.WireGuardProfileList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WireGuardProfilesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val store by lazy { tempFolder.newPreferencesDataStore() }
    private val prefs by lazy { WireGuardPreferences(store) }

    private fun config(key: String) = WireGuardConfig(
        interfaceConfig = WireGuardInterface(privateKey = key, address = listOf("10.0.0.2/32")),
        peers = listOf(WireGuardPeer(publicKey = "pub-$key", allowedIPs = listOf("0.0.0.0/0"))),
    )

    private fun profile(id: String) = WireGuardProfile(id, "name-$id", config("key-$id"))

    private suspend fun storeLegacy(json: String) = store.edit { it[WireGuardPreferences.KEY_WG_CONFIG_JSON] = json }

    @Test
    fun `defaults are direct routing with no profiles`() = runTest {
        assertEquals(WireGuardPreferences.ROUTING_MODE_DIRECT, prefs.getRoutingModeSnapshot())
        assertEquals(emptyList<WireGuardProfile>(), prefs.getWgProfilesSnapshot())
        assertNull(prefs.wgActiveProfileId.first())
        assertNull(prefs.getActiveWgProfileSnapshot())
        assertNull(prefs.getWgConfigJsonSnapshot())
    }

    @Test
    fun `routing mode and lan toggle round trip`() = runTest {
        prefs.setRoutingMode(WireGuardPreferences.ROUTING_MODE_WIREGUARD)
        prefs.setExcludeLan(true)
        assertEquals(WireGuardPreferences.ROUTING_MODE_WIREGUARD, prefs.routingMode.first())
        assertTrue(prefs.excludeLan.first())
    }

    @Test
    fun `add, update and activate profiles`() = runTest {
        prefs.addOrUpdateWgProfile(profile("a"))
        prefs.addOrUpdateWgProfile(profile("b"), makeActive = true)
        prefs.addOrUpdateWgProfile(profile("a").copy(name = "renamed"))

        assertEquals(listOf("renamed", "name-b"), prefs.getWgProfilesSnapshot().map { it.name })
        assertEquals("b", prefs.getActiveWgProfileSnapshot()?.id)
        assertEquals(config("key-b"), WireGuardConfig.fromJson(prefs.getWgConfigJsonSnapshot()!!))

        prefs.setActiveWgProfile("a")
        assertEquals("renamed", prefs.getActiveWgProfileSnapshot()?.name)
    }

    @Test
    fun `active profile falls back to the first when the id is stale`() = runTest {
        prefs.addOrUpdateWgProfile(profile("a"))
        prefs.addOrUpdateWgProfile(profile("b"))
        prefs.setActiveWgProfile("gone")
        assertEquals("a", prefs.getActiveWgProfileSnapshot()?.id)
    }

    @Test
    fun `removing the active profile promotes the next one and the last clears it`() = runTest {
        prefs.addOrUpdateWgProfile(profile("a"), makeActive = true)
        prefs.addOrUpdateWgProfile(profile("b"))

        prefs.removeWgProfile("a")
        assertEquals("b", prefs.wgActiveProfileId.first())
        assertEquals(listOf("b"), prefs.getWgProfilesSnapshot().map { it.id })

        prefs.removeWgProfile("b")
        assertNull(prefs.wgActiveProfileId.first())
        assertTrue(prefs.getWgProfilesSnapshot().isEmpty())
    }

    @Test
    fun `removing an inactive profile keeps the active one`() = runTest {
        prefs.addOrUpdateWgProfile(profile("a"), makeActive = true)
        prefs.addOrUpdateWgProfile(profile("b"))
        prefs.removeWgProfile("b")
        assertEquals("a", prefs.wgActiveProfileId.first())
    }

    @Test
    fun `rename touches only the matching profile`() = runTest {
        prefs.addOrUpdateWgProfile(profile("a"))
        prefs.addOrUpdateWgProfile(profile("b"))
        prefs.renameWgProfile("b", "Work")
        assertEquals(listOf("name-a", "Work"), prefs.wgProfiles.first().map { it.name })
    }

    @Test
    fun `clear removes profiles, active id and legacy config`() = runTest {
        prefs.addOrUpdateWgProfile(profile("a"), makeActive = true)
        storeLegacy(config("legacy").toJson())
        prefs.clearAllWgProfiles()
        val snapshot = store.data.first()
        assertFalse(snapshot.contains(WireGuardPreferences.KEY_WG_PROFILES_JSON))
        assertFalse(snapshot.contains(WireGuardPreferences.KEY_WG_ACTIVE_PROFILE_ID))
        assertFalse(snapshot.contains(WireGuardPreferences.KEY_WG_CONFIG_JSON))
    }

    @Test
    fun `legacy single config is exposed as a Default profile before migration`() = runTest {
        storeLegacy(config("legacy").toJson())
        val active = prefs.getActiveWgProfileSnapshot()!!
        assertEquals("legacy-default", active.id)
        assertEquals("Default", active.name)
        assertEquals(config("legacy"), active.config)
        assertEquals("legacy-default", prefs.wgActiveProfileId.first())
    }

    @Test
    fun `corrupt legacy config reads as no profiles`() = runTest {
        storeLegacy("{not json")
        assertTrue(prefs.getWgProfilesSnapshot().isEmpty())
        assertNull(prefs.getActiveWgProfileSnapshot())
    }

    @Test
    fun `migration moves the legacy config into the profile list`() = runTest {
        storeLegacy(config("legacy").toJson())
        prefs.migrateLegacyWgConfigIfNeeded()

        val snapshot = store.data.first()
        assertFalse(snapshot.contains(WireGuardPreferences.KEY_WG_CONFIG_JSON))
        assertEquals("legacy-default", snapshot[WireGuardPreferences.KEY_WG_ACTIVE_PROFILE_ID])
        val stored = WireGuardProfileList.fromJson(snapshot[WireGuardPreferences.KEY_WG_PROFILES_JSON]!!)
        assertEquals(listOf(WireGuardProfile("legacy-default", "Default", config("legacy"))), stored.profiles)

        prefs.migrateLegacyWgConfigIfNeeded()
        assertEquals(1, prefs.getWgProfilesSnapshot().size)
    }

    @Test
    fun `migration drops a corrupt legacy config`() = runTest {
        storeLegacy("garbage")
        prefs.migrateLegacyWgConfigIfNeeded()
        val snapshot = store.data.first()
        assertFalse(snapshot.contains(WireGuardPreferences.KEY_WG_CONFIG_JSON))
        assertFalse(snapshot.contains(WireGuardPreferences.KEY_WG_PROFILES_JSON))
    }

    @Test
    fun `migration prefers an existing profile list and discards the legacy config`() = runTest {
        prefs.addOrUpdateWgProfile(profile("a"), makeActive = true)
        storeLegacy(config("legacy").toJson())
        prefs.migrateLegacyWgConfigIfNeeded()
        assertEquals(listOf("a"), prefs.getWgProfilesSnapshot().map { it.id })
        assertFalse(store.data.first().contains(WireGuardPreferences.KEY_WG_CONFIG_JSON))
    }

    @Test
    fun `migration without any config is a no-op`() = runTest {
        prefs.migrateLegacyWgConfigIfNeeded()
        assertTrue(store.data.first().asMap().isEmpty())
    }

    @Test
    fun `saving a profile over a legacy config keeps the legacy one`() = runTest {
        storeLegacy(config("legacy").toJson())
        prefs.addOrUpdateWgProfile(profile("a"))
        assertEquals(listOf("legacy-default", "a"), prefs.getWgProfilesSnapshot().map { it.id })
        assertFalse(store.data.first().contains(WireGuardPreferences.KEY_WG_CONFIG_JSON))
    }

    @Test
    fun `profile list JSON tolerates unknown keys and garbage`() {
        val list = WireGuardProfileList(listOf(profile("a")))
        assertEquals(list, WireGuardProfileList.fromJson(list.toJson()))
        assertEquals(WireGuardProfileList(), WireGuardProfileList.fromJson("[]"))
        assertEquals(
            list,
            WireGuardProfileList.fromJson(list.toJson().replaceFirst("{", "{\"schema\":2,")),
        )
        assertTrue(WireGuardProfile.newId() != WireGuardProfile.newId())
    }
}
