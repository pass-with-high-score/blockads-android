package app.pwhs.blockads.ui.onboarding.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.pwhs.blockads.ui.theme.NeonGreen

data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val accentColor: Color = NeonGreen
)