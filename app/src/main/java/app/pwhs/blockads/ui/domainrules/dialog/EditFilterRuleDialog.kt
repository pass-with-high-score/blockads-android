package app.pwhs.blockads.ui.domainrules.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun EditFilterRuleDialog(
    initialDomain: String,
    initialType: String = "HOST-SUFFIX",
    initialPolicy: String,
    onDismiss: () -> Unit,
    onSave: (type: String, param: String, policy: String) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterRuleDialog(
        initialType = initialType,
        initialParam = initialDomain,
        initialPolicy = initialPolicy,
        onDismiss = onDismiss,
        onSave = onSave,
        modifier = modifier
    )
}
