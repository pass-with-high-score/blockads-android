package app.pwhs.blockads.ui.httpsfiltering.wizard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.httpsfiltering.CertStatus
import app.pwhs.blockads.ui.httpsfiltering.wizard.component.CertWizardHeroPane
import app.pwhs.blockads.ui.httpsfiltering.wizard.component.WizardExplanationPage
import app.pwhs.blockads.ui.httpsfiltering.wizard.component.WizardInstallCaPage
import app.pwhs.blockads.ui.httpsfiltering.wizard.component.WizardSaveCaPage
import app.pwhs.blockads.ui.httpsfiltering.wizard.component.WizardSecurityPage
import app.pwhs.blockads.ui.httpsfiltering.wizard.component.WizardStepIndicator
import app.pwhs.blockads.ui.httpsfiltering.wizard.component.WizardVerifyPage
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertInstallationWizardScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CertInstallationWizardViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.processIntent(CertInstallationWizardUiIntent.VerifyCert)
    }

    val safExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")
    ) { uri ->
        uri?.let { viewModel.processIntent(CertInstallationWizardUiIntent.ExportCertToUri(it)) }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.processIntent(CertInstallationWizardUiIntent.ExportCert)
        } else {
            safExportLauncher.launch(uiState.fileName)
        }
    }

    // Handle system back navigation to navigate between steps or exit
    BackHandler(enabled = true) {
        if (uiState.currentStep.ordinal > 0) {
            viewModel.processIntent(CertInstallationWizardUiIntent.PrevStep)
        } else {
            onNavigateBack()
        }
    }

    val filenameCopiedMsg = stringResource(R.string.https_wizard_filename_copied)

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is CertInstallationWizardUiEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is CertInstallationWizardUiEffect.OpenSettings -> {
                    try {
                        settingsLauncher.launch(effect.intent)
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Cannot open Settings: ${e.message}")
                    }
                }
                CertInstallationWizardUiEffect.FinishAndNavigateBack -> {
                    onNavigateBack()
                }
                is CertInstallationWizardUiEffect.CopyToClipboard -> {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    val clip = android.content.ClipData.newPlainText("cert_filename", effect.text)
                    clipboard?.setPrimaryClip(clip)
                    snackbarHostState.showSnackbar(filenameCopiedMsg)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.https_wizard_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val isTwoPane = rememberIsExpandedLayout(maxWidth)

            val wizardStepsContent: @Composable (Modifier) -> Unit = { contentModifier ->
                Column(modifier = contentModifier) {
                    // Segmented Step Indicator
                    WizardStepIndicator(
                        currentStep = uiState.currentStep,
                        onStepClick = { step ->
                            viewModel.processIntent(CertInstallationWizardUiIntent.GoToStep(step))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Slide & Fade step transition
                    AnimatedContent(
                        targetState = uiState.currentStep,
                        transitionSpec = {
                            if (targetState.ordinal > initialState.ordinal) {
                                (slideInHorizontally(tween(280)) { it / 2 } + fadeIn(tween(280))) togetherWith
                                        (slideOutHorizontally(tween(220)) { -it / 2 } + fadeOut(tween(220)))
                            } else {
                                (slideInHorizontally(tween(280)) { -it / 2 } + fadeIn(tween(280))) togetherWith
                                        (slideOutHorizontally(tween(220)) { it / 2 } + fadeOut(tween(220)))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        label = "WizardStepSlideTransition"
                    ) { step ->
                        when (step) {
                            WizardStep.EXPLANATION -> {
                                WizardExplanationPage()
                            }
                            WizardStep.SECURITY -> {
                                WizardSecurityPage()
                            }
                            WizardStep.SAVE_CA -> {
                                WizardSaveCaPage(
                                    fileName = uiState.fileName,
                                    isExported = uiState.isCertExported,
                                    onExport = {
                                        val needsLegacyPermission =
                                            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
                                                androidx.core.content.ContextCompat.checkSelfPermission(
                                                    context,
                                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (needsLegacyPermission) {
                                            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        } else {
                                            viewModel.processIntent(CertInstallationWizardUiIntent.ExportCert)
                                        }
                                    },
                                    onCopyFileName = {
                                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                        val clip = android.content.ClipData.newPlainText("cert_filename", uiState.fileName)
                                        clipboard?.setPrimaryClip(clip)
                                    }
                                )
                            }
                            WizardStep.INSTALL_CA -> {
                                WizardInstallCaPage(
                                    brandName = uiState.brandName,
                                    installSteps = uiState.installSteps,
                                    onOpenSettings = { viewModel.processIntent(CertInstallationWizardUiIntent.OpenSecuritySettings) }
                                )
                            }
                            WizardStep.VERIFY -> {
                                WizardVerifyPage(
                                    certStatus = uiState.certStatus,
                                    onVerify = { viewModel.processIntent(CertInstallationWizardUiIntent.VerifyCert) },
                                    onFinish = { viewModel.processIntent(CertInstallationWizardUiIntent.FinishWizard) },
                                    onPrevStep = { viewModel.processIntent(CertInstallationWizardUiIntent.PrevStep) }
                                )
                            }
                        }
                    }

                    val hideBottomNav = uiState.currentStep == WizardStep.VERIFY && uiState.certStatus == CertStatus.INSTALLED
                    if (!hideBottomNav) {
                        WizardBottomNav(
                            currentStep = uiState.currentStep,
                            isTwoPane = isTwoPane,
                            onPrev = { viewModel.processIntent(CertInstallationWizardUiIntent.PrevStep) },
                            onNext = { viewModel.processIntent(CertInstallationWizardUiIntent.NextStep) }
                        )
                    }
                }
            }

            if (isTwoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CertWizardHeroPane(
                        currentStep = uiState.currentStep,
                        certStatus = uiState.certStatus,
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                    )

                    wizardStepsContent(
                        Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                            .widthIn(max = 560.dp)
                    )
                }
            } else {
                wizardStepsContent(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun WizardBottomNav(
    currentStep: WizardStep,
    isTwoPane: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentStep.ordinal > 0) {
            OutlinedButton(
                onClick = onPrev,
                modifier = Modifier
                    .then(if (!isTwoPane) Modifier.weight(1f) else Modifier.widthIn(min = 132.dp))
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.https_wizard_prev),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (currentStep != WizardStep.VERIFY) {
            Button(
                onClick = onNext,
                modifier = Modifier
                    .then(if (!isTwoPane) Modifier.weight(1f) else Modifier.widthIn(min = 156.dp))
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = stringResource(R.string.https_wizard_next),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
