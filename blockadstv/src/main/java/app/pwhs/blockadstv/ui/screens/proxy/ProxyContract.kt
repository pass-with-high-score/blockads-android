package app.pwhs.blockadstv.ui.screens.proxy

import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.data.entities.ProxyProfile
import app.pwhs.blockadstv.data.entities.ProxySubscription
import app.pwhs.blockadstv.utils.NodePingResult

data class ProxyUiState(
    val routingMode: String = TvPreferences.ROUTING_MODE_DIRECT,
    val isRootAvailable: Boolean = false,
    val profiles: List<ProxyProfile> = emptyList(),
    val activeProfileId: String? = null,
    val subscriptions: List<ProxySubscription> = emptyList(),
    val pingResults: Map<String, NodePingResult> = emptyMap(),
    val isUpdatingSubscription: Boolean = false,
    val showAddNodeDialog: Boolean = false,
    val showImportDialog: Boolean = false,
    val showRootWarningDialog: Boolean = false,
    val dialogError: String? = null,
)

sealed interface ProxyUiIntent {
    data class SelectRoutingMode(val mode: String) : ProxyUiIntent
    data class SelectActiveProfile(val id: String) : ProxyUiIntent
    data class DeleteProfile(val id: String) : ProxyUiIntent
    data class PingProfile(val profile: ProxyProfile) : ProxyUiIntent
    data object PingAll : ProxyUiIntent
    data class ImportLink(val link: String) : ProxyUiIntent
    data class ImportSubscription(val url: String, val name: String = "") : ProxyUiIntent
    data class DeleteSubscription(val id: String) : ProxyUiIntent
    data class ShowAddNodeDialog(val show: Boolean) : ProxyUiIntent
    data class ShowImportDialog(val show: Boolean) : ProxyUiIntent
    data object DismissRootWarning : ProxyUiIntent
}

sealed interface ProxyUiEffect {
    data class ShowToast(val message: String) : ProxyUiEffect
    data object RequestRestart : ProxyUiEffect
}
