package app.pwhs.blockads.ui.domainrules.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pwhs.blockads.ui.domainrules.InstalledAppOption

@Composable
fun EditFilterRuleDialog(
    initialDomain: String,
    initialType: String = "HOST-SUFFIX",
    initialPolicy: String,
    initialPackageName: String = "",
    installedApps: List<InstalledAppOption> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (type: String, param: String, policy: String, packageName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterRuleDialog(
        initialType = initialType,
        initialParam = initialDomain,
        initialPolicy = initialPolicy,
        initialPackageName = initialPackageName,
        installedApps = installedApps,
        onDismiss = onDismiss,
        onSave = onSave,
        modifier = modifier
    )
}
