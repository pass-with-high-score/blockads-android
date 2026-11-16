package app.pwhs.blockads.ui.httpsfiltering.wizard.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.ui.httpsfiltering.CertStatus
import app.pwhs.blockads.ui.httpsfiltering.wizard.WizardStep
import app.pwhs.blockads.ui.onboarding.component.OnboardingGlowIcon
import app.pwhs.blockads.ui.theme.AccentBlue

/**
 * Left-hand visual hero pane for the Cert Installation Wizard on unfolded foldables (`width >= 600.dp`).
 */
@Composable
fun CertWizardHeroPane(
    currentStep: WizardStep,
    certStatus: CertStatus,
    modifier: Modifier = Modifier
) {
    val totalSteps = WizardStep.entries.size

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(200))
                },
                label = "cert_wizard_hero_icon"
            ) { step ->
                val (icon, tint) = when (step) {
                    WizardStep.EXPLANATION -> Icons.Filled.Https to MaterialTheme.colorScheme.primary
                    WizardStep.SECURITY -> Icons.Filled.Lock to AccentBlue
                    WizardStep.SAVE_CA -> Icons.Filled.Download to MaterialTheme.colorScheme.primary
                    WizardStep.INSTALL_CA -> Icons.Filled.SettingsApplications to AccentBlue
                    WizardStep.VERIFY -> Icons.Filled.VerifiedUser to if (certStatus == CertStatus.INSTALLED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        AccentBlue
                    }
                }
                OnboardingGlowIcon(
                    imageVector = icon,
                    accentColor = tint,
                    outerSize = 180.dp,
                    innerSize = 128.dp,
                    iconSize = 68.dp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = "${currentStep.ordinal + 1} / $totalSteps",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}
