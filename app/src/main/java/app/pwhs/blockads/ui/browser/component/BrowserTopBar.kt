package app.pwhs.blockads.ui.browser.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTopBar(
    displayUrl: String,
    progress: Int,
    isLoading: Boolean,
    blockedCount: Int,
    isDesktopMode: Boolean,
    onUrlSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onClearData: () -> Unit,
    onOpenExternal: () -> Unit,
    onCloseBrowser: () -> Unit,
    onEnterPip: () -> Unit = {},
    onOpenShieldSheet: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var text by remember(displayUrl) { mutableStateOf(displayUrl) }
    var menuExpanded by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val domain = remember(displayUrl) {
        runCatching {
            val uri = android.net.Uri.parse(displayUrl)
            uri.host?.removePrefix("www.") ?: displayUrl
        }.getOrDefault(displayUrl)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Secure Connection",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (!isEditing && text == displayUrl) {
                                Text(
                                    text = domain,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { isEditing = true }
                                )
                            } else {
                                BasicTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            isEditing = false
                                            focusManager.clearFocus()
                                            onUrlSubmit(text)
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        if (text.isNotEmpty() && (isEditing || text != displayUrl)) {
                            IconButton(
                                onClick = {
                                    text = ""
                                    isEditing = true
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Shield blocked count badge
                        if (blockedCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { onOpenShieldSheet() }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "$blockedCount",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onCloseBrowser) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            actions = {
                IconButton(onClick = onReload) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload"
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More"
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Chế độ Thu nhỏ (PiP)") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_pip),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onEnterPip()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isDesktopMode) "Giao diện Di động" else "Giao diện Máy tính (Desktop)") },
                            leadingIcon = { Icon(Icons.Default.DesktopWindows, null) },
                            onClick = {
                                menuExpanded = false
                                onToggleDesktopMode()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mở trên trình duyệt ngoài") },
                            leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) },
                            onClick = {
                                menuExpanded = false
                                onOpenExternal()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Xóa Cookie & Cache") },
                            leadingIcon = { Icon(Icons.Default.Clear, null) },
                            onClick = {
                                menuExpanded = false
                                onClearData()
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Web load progress indicator
        AnimatedVisibility(visible = isLoading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
