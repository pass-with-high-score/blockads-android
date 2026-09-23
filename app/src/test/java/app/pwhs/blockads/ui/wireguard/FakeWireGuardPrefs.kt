package app.pwhs.blockads.ui.wireguard

import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.WireGuardConfig
import app.pwhs.blockads.data.entities.WireGuardInterface
import app.pwhs.blockads.data.entities.WireGuardPeer
import app.pwhs.blockads.data.entities.WireGuardProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

const val KEY_A = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
const val KEY_B = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="

fun wgProfile(id: String, name: String = id) = WireGuardProfile(
    id = id,
    name = name,
    config = WireGuardConfig(
        interfaceConfig = WireGuardInterface(privateKey = KEY_A, address = listOf("10.0.0.2/32")),
        peers = listOf(WireGuardPeer(publicKey = KEY_B, endpoint = "vpn.example:51820", allowedIPs = listOf("0.0.0.0/0"))),
    ),
)

/** AppPreferences mock whose WireGuard profile calls follow WireGuardPreferences' list semantics in memory. */
class FakeWireGuardPrefs(profiles: List<WireGuardProfile> = emptyList(), activeId: String? = null) {
    val profiles = MutableStateFlow(profiles)
    val activeId = MutableStateFlow(activeId)
    val routingMode = MutableStateFlow(AppPreferences.ROUTING_MODE_DIRECT)
    val httpsFiltering = MutableStateFlow(false)

    private fun active() = profiles.value.let { list -> list.firstOrNull { it.id == activeId.value } ?: list.firstOrNull() }

    val prefs: AppPreferences = mockk(relaxed = true) {
        every { wgProfiles } returns this@FakeWireGuardPrefs.profiles
        every { wgActiveProfileId } returns this@FakeWireGuardPrefs.activeId
        every { splitDnsZones } returns MutableStateFlow("corp.example")
        every { excludeLan } returns MutableStateFlow(true)
        every { allowAppBypass } returns MutableStateFlow(false)
        coEvery { getRoutingModeSnapshot() } answers { this@FakeWireGuardPrefs.routingMode.value }
        coEvery { setRoutingMode(any()) } coAnswers { this@FakeWireGuardPrefs.routingMode.value = firstArg() }
        coEvery { getHttpsFilteringEnabledSnapshot() } answers { this@FakeWireGuardPrefs.httpsFiltering.value }
        coEvery { setHttpsFilteringEnabled(any()) } coAnswers { this@FakeWireGuardPrefs.httpsFiltering.value = firstArg() }
        coEvery { getWgProfilesSnapshot() } answers { this@FakeWireGuardPrefs.profiles.value }
        coEvery { getActiveWgProfileSnapshot() } answers { active() }
        coEvery { addOrUpdateWgProfile(any(), any()) } coAnswers {
            val profile = firstArg<WireGuardProfile>()
            val list = this@FakeWireGuardPrefs.profiles.value
            this@FakeWireGuardPrefs.profiles.value =
                if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
            if (secondArg()) this@FakeWireGuardPrefs.activeId.value = profile.id
        }
        coEvery { removeWgProfile(any()) } coAnswers {
            val id = firstArg<String>()
            this@FakeWireGuardPrefs.profiles.value = this@FakeWireGuardPrefs.profiles.value.filterNot { it.id == id }
            if (this@FakeWireGuardPrefs.activeId.value == id) {
                this@FakeWireGuardPrefs.activeId.value = this@FakeWireGuardPrefs.profiles.value.firstOrNull()?.id
            }
        }
        coEvery { setActiveWgProfile(any()) } coAnswers { this@FakeWireGuardPrefs.activeId.value = firstArg() }
        coEvery { renameWgProfile(any(), any()) } coAnswers {
            val id = firstArg<String>()
            this@FakeWireGuardPrefs.profiles.value =
                this@FakeWireGuardPrefs.profiles.value.map { if (it.id == id) it.copy(name = secondArg()) else it }
        }
    }
}
