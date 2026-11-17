package app.pwhs.blockads.ui.firewall.component

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.firewall.prompt.FirewallPromptItem
import app.pwhs.blockads.ui.theme.BlockadsTheme
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallPermissionDialog(
    pendingItems: List<FirewallPromptItem>,
    onAlwaysAllow: (String) -> Unit,
    onAllowOnce: (String) -> Unit,
    onBlock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        FirewallPermissionSheetContent(
            pendingItems = pendingItems,
            onAlwaysAllow = onAlwaysAllow,
            onAllowOnce = onAllowOnce,
            onBlock = onBlock,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun FirewallPermissionSheetContent(
    pendingItems: List<FirewallPromptItem>,
    onAlwaysAllow: (String) -> Unit,
    onAllowOnce: (String) -> Unit,
    onBlock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val titleText = if (pendingItems.size == 1) {
        stringResource(R.string.firewall_prompt_dialog_title, pendingItems.first().appLabel)
    } else {
        stringResource(R.string.firewall_prompt_dialog_multi_title, pendingItems.size)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.firewall_prompt_later))
            }
        }

        Text(
            text = stringResource(R.string.firewall_prompt_dialog_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(pendingItems, key = { it.packageName }) { item ->
                FirewallPromptRow(
                    item = item,
                    onAlwaysAllow = { onAlwaysAllow(item.packageName) },
                    onAllowOnce = { onAllowOnce(item.packageName) },
                    onBlock = { onBlock(item.packageName) }
                )
            }
        }
    }
}

@Composable
private fun FirewallPromptRow(
    item: FirewallPromptItem,
    onAlwaysAllow: () -> Unit,
    onAllowOnce: () -> Unit,
    onBlock: () -> Unit
) {
    val formattedTime = remember(item.timestamp) {
        if (item.timestamp > 0L) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timestamp))
        } else {
            ""
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PromptAppIcon(item.icon, item.appLabel)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.appLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (formattedTime.isNotEmpty()) {
                            Spacer(modifier = Modifier.size(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAlwaysAllow,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.firewall_prompt_always_allow),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = onAllowOnce,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.firewall_prompt_allow_once),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                TextButton(
                    onClick = onBlock,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.firewall_prompt_action_block),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptAppIcon(appIcon: Drawable?, appLabel: String) {
    if (appIcon != null) {
        Image(
            painter = rememberDrawablePainter(drawable = appIcon),
            contentDescription = appLabel,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
        )
    } else {
        Icon(
            imageVector = Icons.Default.Android,
            contentDescription = appLabel,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun SheetPreviewLight() {
    BlockadsTheme(themeMode = "light") {
        FirewallPermissionSheetContent(
            pendingItems = listOf(
                FirewallPromptItem("com.sec.android.app.shealth", "Samsung Health", null, 1731834765000L),
                FirewallPromptItem("com.example.browser", "Browser Beta", null, 1731834720000L)
            ),
            onAlwaysAllow = {},
            onAllowOnce = {},
            onBlock = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "Dark", showBackground = true)
@Composable
private fun SheetPreviewDark() {
    BlockadsTheme(themeMode = "dark") {
        FirewallPermissionSheetContent(
            pendingItems = listOf(
                FirewallPromptItem("com.sec.android.app.shealth", "Samsung Health", null, 1731834765000L),
                FirewallPromptItem("com.example.browser", "Browser Beta", null, 1731834720000L)
            ),
            onAlwaysAllow = {},
            onAllowOnce = {},
            onBlock = {},
            onDismiss = {}
        )
    }
}
