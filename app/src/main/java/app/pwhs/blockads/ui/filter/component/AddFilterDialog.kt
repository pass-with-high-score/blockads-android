package app.pwhs.blockads.ui.filter.component

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@Composable
fun AddFilterDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit,
    existingUrls: List<String> = emptyList(),
    isValidating: Boolean = false
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    val urlTrimmed = url.trim()
    val nameTrimmed = name.trim()

    val urlError = when {
        urlTrimmed.isEmpty() -> null
        !urlTrimmed.startsWith("http://") && !urlTrimmed.startsWith("https://") ->
            stringResource(R.string.filter_error_invalid_url)
        !Patterns.WEB_URL.matcher(urlTrimmed).matches() ->
            stringResource(R.string.filter_error_invalid_url)
        existingUrls.any { it.equals(urlTrimmed, ignoreCase = true) } ->
            stringResource(R.string.filter_error_duplicate_url)
        else -> null
    }

    val isValid = nameTrimmed.isNotBlank() && urlTrimmed.isNotBlank() && urlError == null

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        onDismissRequest = { if (!isValidating) onDismiss() },
        title = { Text(stringResource(R.string.settings_add_filter_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_add_filter_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isValidating
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_add_filter_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    isError = urlError != null,
                    supportingText = urlError?.let { err ->
                        { Text(err, color = MaterialTheme.colorScheme.error) }
                    },
                    enabled = !isValidating
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (isValid && !isValidating) onAdd(nameTrimmed, urlTrimmed) },
                enabled = isValid && !isValidating
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.settings_add))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isValidating
            ) { Text(stringResource(R.string.settings_cancel)) }
        }
    )
}
