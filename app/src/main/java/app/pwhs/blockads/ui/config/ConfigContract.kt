package app.pwhs.blockads.ui.config

import app.pwhs.blockads.data.entities.ConfigProfile

data class ConfigUiState(
    val configs: List<ConfigProfile> = emptyList(),
    val activeConfig: ConfigProfile? = null,
    val isLoading: Boolean = false,
    val isUpdating: Boolean = false,
    val isEditorOpen: Boolean = false,
    val showImportDialog: Boolean = false,
    val showProfilesSheet: Boolean = false,
    val showMiscSettingsDialog: Boolean = false,
    val showSnippetsSheet: Boolean = false,
    val showResetConfirmDialog: Boolean = false,
    val editingConfig: ConfigProfile? = null,
)

sealed interface ConfigUiIntent {
    data class SelectActive(val configId: Long) : ConfigUiIntent
    data class AddRemoteConfig(val name: String, val url: String) : ConfigUiIntent
    data class AddLocalConfig(val name: String, val content: String) : ConfigUiIntent
    data class UpdateConfig(val configId: Long, val name: String, val content: String) : ConfigUiIntent
    data class DeleteConfig(val config: ConfigProfile) : ConfigUiIntent
    data object ShowAddDialog : ConfigUiIntent
    data object DismissAddDialog : ConfigUiIntent
    data class EditConfig(val config: ConfigProfile) : ConfigUiIntent
    data object EditActiveConfig : ConfigUiIntent
    data object CloseEditor : ConfigUiIntent
    data class RefreshRemote(val configId: Long) : ConfigUiIntent
    data object LoadSample : ConfigUiIntent
    data object ResetActiveConfig : ConfigUiIntent
    data object ShowImportDialog : ConfigUiIntent
    data object DismissImportDialog : ConfigUiIntent
    data object ShowProfilesSheet : ConfigUiIntent
    data object DismissProfilesSheet : ConfigUiIntent
    data object ShowMiscSettingsDialog : ConfigUiIntent
    data object DismissMiscSettingsDialog : ConfigUiIntent
    data object ShowSnippetsSheet : ConfigUiIntent
    data object DismissSnippetsSheet : ConfigUiIntent
    data object ShowResetConfirmDialog : ConfigUiIntent
    data object DismissResetConfirmDialog : ConfigUiIntent
    data class ToggleAutoUpdate(val configId: Long, val enabled: Boolean) : ConfigUiIntent
}

sealed interface ConfigUiEffect {
    data class ShowToast(val messageRes: Int) : ConfigUiEffect
    data class ShowMessage(val message: String) : ConfigUiEffect
    data class CopyToClipboard(val text: String) : ConfigUiEffect
}

