package app.pwhs.blockads.ui.config

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.config.component.ProfileBottomActions
import app.pwhs.blockads.ui.config.component.ProfileGridActions
import app.pwhs.blockads.ui.config.component.ProfileHeader
import app.pwhs.blockads.ui.config.dialogs.ConfigImportDialog
import app.pwhs.blockads.ui.config.dialogs.ConfigMiscSettingsDialog
import app.pwhs.blockads.ui.config.dialogs.ConfigProfilesSheet
import app.pwhs.blockads.ui.config.dialogs.ConfigResetConfirmDialog
import app.pwhs.blockads.ui.config.editor.ConfigEditorScreen
import org.koin.androidx.compose.koinViewModel

@Composable
fun ConfigScreen(
    modifier: Modifier = Modifier,
    viewModel: ConfigViewModel = koinViewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ConfigUiEffect.ShowToast -> Toast.makeText(context, effect.messageRes, Toast.LENGTH_SHORT).show()
                is ConfigUiEffect.ShowMessage -> Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                is ConfigUiEffect.CopyToClipboard -> {
                    clipboardManager.setText(AnnotatedString(effect.text))
                    Toast.makeText(context, R.string.profile_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (uiState.isEditorOpen && uiState.editingConfig != null) {
        ConfigEditorScreen(
            config = uiState.editingConfig!!,
            onClose = { viewModel.onIntent(ConfigUiIntent.CloseEditor) },
            onSave = { name, content ->
                viewModel.onIntent(ConfigUiIntent.UpdateConfig(uiState.editingConfig!!.id, name, content))
                viewModel.onIntent(ConfigUiIntent.CloseEditor)
            }
        )
        return
    }

    Scaffold(modifier = modifier) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    ProfileHeader(
                        activeConfig = uiState.activeConfig,
                        onNavigateBack = onNavigateBack,
                        onOpenProfiles = { viewModel.onIntent(ConfigUiIntent.ShowProfilesSheet) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    ProfileGridActions(
                        onSnippetsClick = { viewModel.onIntent(ConfigUiIntent.ShowProfilesSheet) },
                        onEditClick = { viewModel.onIntent(ConfigUiIntent.EditActiveConfig) },
                        onImportClick = { viewModel.onIntent(ConfigUiIntent.ShowImportDialog) },
                        onExportClick = {
                            val active = uiState.activeConfig
                            if (active != null) {
                                clipboardManager.setText(AnnotatedString(active.content))
                                Toast.makeText(context, R.string.profile_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDownloadClick = {
                            val active = uiState.activeConfig
                            if (active?.remoteUrl != null) {
                                viewModel.onIntent(ConfigUiIntent.RefreshRemote(active.id))
                            } else {
                                viewModel.onIntent(ConfigUiIntent.ShowImportDialog)
                            }
                        },
                        onSampleClick = { viewModel.onIntent(ConfigUiIntent.LoadSample) },
                        onResetClick = { viewModel.onIntent(ConfigUiIntent.ShowResetConfirmDialog) }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    ProfileBottomActions(
                        onSwitchProfileClick = { viewModel.onIntent(ConfigUiIntent.ShowProfilesSheet) },
                        onMiscSettingsClick = { viewModel.onIntent(ConfigUiIntent.ShowMiscSettingsDialog) }
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            if (uiState.isUpdating) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            if (uiState.showProfilesSheet) {
                ConfigProfilesSheet(
                    configs = uiState.configs,
                    activeConfig = uiState.activeConfig,
                    onSelectActive = { viewModel.onIntent(ConfigUiIntent.SelectActive(it)) },
                    onDeleteConfig = { viewModel.onIntent(ConfigUiIntent.DeleteConfig(it)) },
                    onAddClick = { viewModel.onIntent(ConfigUiIntent.ShowImportDialog) },
                    onDismiss = { viewModel.onIntent(ConfigUiIntent.DismissProfilesSheet) }
                )
            }

            if (uiState.showImportDialog) {
                ConfigImportDialog(
                    onDismiss = { viewModel.onIntent(ConfigUiIntent.DismissImportDialog) },
                    onImportRemote = { name, url ->
                        viewModel.onIntent(ConfigUiIntent.AddRemoteConfig(name, url))
                    },
                    onImportLocal = { name, content ->
                        viewModel.onIntent(ConfigUiIntent.AddLocalConfig(name, content))
                    }
                )
            }

            if (uiState.showResetConfirmDialog) {
                ConfigResetConfirmDialog(
                    onConfirm = { viewModel.onIntent(ConfigUiIntent.ResetActiveConfig) },
                    onDismiss = { viewModel.onIntent(ConfigUiIntent.DismissResetConfirmDialog) }
                )
            }

            if (uiState.showMiscSettingsDialog) {
                ConfigMiscSettingsDialog(
                    activeConfig = uiState.activeConfig,
                    onToggleAutoUpdate = { enabled ->
                        uiState.activeConfig?.let {
                            viewModel.onIntent(ConfigUiIntent.ToggleAutoUpdate(it.id, enabled))
                        }
                    },
                    onDismiss = { viewModel.onIntent(ConfigUiIntent.DismissMiscSettingsDialog) }
                )
            }
        }
    }
}
