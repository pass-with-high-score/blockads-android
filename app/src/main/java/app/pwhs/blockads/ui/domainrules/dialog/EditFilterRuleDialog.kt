package app.pwhs.blockads.ui.domainrules.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pwhs.blockads.data.entities.ConfigProfile

@Composable
fun EditFilterRuleDialog(
    initialDomain: String,
    initialType: String = "HOST-SUFFIX",
    initialPolicy: String,
    activeConfig: ConfigProfile?,
    allConfigs: List<ConfigProfile>,
    onDismiss: () -> Unit,
    onSave: (type: String, param: String, policy: String, configId: Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterRuleDialog(
        initialType = initialType,
        initialParam = initialDomain,
        initialPolicy = initialPolicy,
        initialConfigId = activeConfig?.id,
        activeConfig = activeConfig,
        allConfigs = allConfigs,
        onDismiss = onDismiss,
        onSave = onSave,
        modifier = modifier
    )
}
