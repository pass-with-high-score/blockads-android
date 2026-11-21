package app.pwhs.blockads.ui.proxyvpn.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProxyChoiceBottomSheet(
    onDismiss: () -> Unit,
    onImportLink: () -> Unit,
    onImportFile: () -> Unit,
    onAddCustom: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.proxy_add_choice_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            ChoiceOptionCard(
                icon = Icons.Default.ContentPaste,
                title = stringResource(R.string.proxy_add_choice_import),
                desc = stringResource(R.string.proxy_add_choice_import_desc),
                onClick = {
                    onDismiss()
                    onImportLink()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            ChoiceOptionCard(
                icon = Icons.Default.FileOpen,
                title = stringResource(R.string.proxy_add_choice_wireguard_file),
                desc = stringResource(R.string.proxy_add_choice_wireguard_file_desc),
                onClick = {
                    onDismiss()
                    onImportFile()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            ChoiceOptionCard(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.proxy_add_choice_custom),
                desc = stringResource(R.string.proxy_add_choice_custom_desc),
                onClick = {
                    onDismiss()
                    onAddCustom()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            ChoiceOptionCard(
                icon = Icons.Default.RssFeed,
                title = "Subscriptions",
                desc = "Import and manage remote node subscription URLs",
                onClick = {
                    onDismiss()
                    onOpenSubscriptions()
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ChoiceOptionCard(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
