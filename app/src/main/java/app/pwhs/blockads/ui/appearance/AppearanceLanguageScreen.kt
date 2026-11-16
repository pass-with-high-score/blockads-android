package app.pwhs.blockads.ui.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.appearance.component.AppearanceScaffold
import app.pwhs.blockads.ui.appearance.component.LanguageSelectionCard
import org.koin.androidx.compose.koinViewModel

@Composable
fun AppearanceLanguageScreen(
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    onNavigateBack: () -> Unit = {},
    viewModel: AppearanceViewModel = koinViewModel()
) {
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()

    AppearanceScaffold(
        title = stringResource(R.string.settings_language),
        showBackButton = showBackButton,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) {
        LanguageSelectionCard(
            currentLanguage = appLanguage,
            onSelectLanguage = { viewModel.setAppLanguage(it) }
        )
    }
}
