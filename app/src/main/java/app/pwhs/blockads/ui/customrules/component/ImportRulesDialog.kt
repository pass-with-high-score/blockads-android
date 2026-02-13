package app.pwhs.blockads.ui.customrules.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun ImportRulesDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var rulesText by remember { mutableStateOf("") }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_rules)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.import_rules_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = rulesText,
                    onValueChange = { rulesText = it },
                    label = { Text(stringResource(R.string.rules)) },
                    placeholder = { Text("||example1.com^\n||example2.com^") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 5
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(rulesText) },
                enabled = rulesText.isNotBlank()
            ) {
                Text(stringResource(R.string.import_label))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
