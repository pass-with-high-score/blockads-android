package app.pwhs.blockadstv.ui.screens.wireguard

import app.pwhs.blockadstv.data.entities.WireGuardProfile
import app.pwhs.blockadstv.data.geoip.GeoIpDatabaseInfo
import app.pwhs.blockadstv.utils.NodePingResult

data class WireGuardUiState(
    val isWireGuardModeActive: Boolean = false,
    val profiles: List<WireGuardProfile> = emptyList(),
    val activeProfileId: String? = null,
    val pingResults: Map<String, NodePingResult> = emptyMap(),
    val countryCodes: Map<String, String> = emptyMap(),
    val geoIpInfo: GeoIpDatabaseInfo? = null,
    val isUpdatingGeoIp: Boolean = false,
    val geoIpAutoUpdate: Boolean = true,
    val showImportDialog: Boolean = false,
    val showRenameDialogFor: WireGuardProfile? = null,
    val dialogError: String? = null,
)

sealed interface WireGuardUiIntent {
    data class SetWireGuardMode(val enabled: Boolean) : WireGuardUiIntent
    data class SelectProfile(val id: String) : WireGuardUiIntent
    data class DeleteProfile(val id: String) : WireGuardUiIntent
    data class RenameProfile(val id: String, val name: String) : WireGuardUiIntent
    data class PingProfile(val profile: WireGuardProfile) : WireGuardUiIntent
    data object PingAll : WireGuardUiIntent
    data class ImportConfigOrUri(val input: String, val name: String = "") : WireGuardUiIntent
    data class ShowImportDialog(val show: Boolean) : WireGuardUiIntent
    data class ShowRenameDialog(val profile: WireGuardProfile?) : WireGuardUiIntent
    data object UpdateGeoIpDatabase : WireGuardUiIntent
    data class SetGeoIpAutoUpdate(val enabled: Boolean) : WireGuardUiIntent
}

sealed interface WireGuardUiEffect {
    data class ShowToast(val message: String) : WireGuardUiEffect
    data object RequestRestart : WireGuardUiEffect
}
