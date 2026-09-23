package app.pwhs.blockads.ui.httpsfiltering.wizard.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.httpsfiltering.wizard.WizardStep

@Composable
fun WizardStepIndicator(
    currentStep: WizardStep,
    onStepClick: (WizardStep) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalSteps = WizardStep.entries.size
    val currentStepNumber = currentStep.stepIndex + 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        // Step label & title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.https_wizard_step_indicator_format,
                    currentStepNumber,
                    totalSteps,
                    stringResource(currentStep.titleRes)
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "${(currentStepNumber * 100) / totalSteps}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Segmented Progress Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WizardStep.entries.forEach { step ->
                val isCompleted = step.stepIndex < currentStep.stepIndex
                val isCurrent = step == currentStep

                val segmentColor by animateColorAsState(
                    targetValue = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isCompleted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    },
                    animationSpec = tween(300),
                    label = "segmentColor"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(segmentColor)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true),
                            onClick = { onStepClick(step) }
                        )
                )
            }
        }
    }
}
