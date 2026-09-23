package app.pwhs.blockads.ui.settings.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    )
    val cardBorder = androidx.compose.foundation.BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
    )
    val cardShape = RoundedCornerShape(16.dp)

    if (onClick != null) {
        Card(
            onClick = onClick,
            colors = cardColors,
            border = cardBorder,
            shape = cardShape,
            modifier = modifier
        ) {
            content()
        }
    } else {
        Card(
            colors = cardColors,
            border = cardBorder,
            shape = cardShape,
            modifier = modifier
        ) {
            content()
        }
    }
}
