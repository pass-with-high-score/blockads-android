package app.pwhs.blockads.ui.proxyvpn.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.ProxyProtocol

val ProxyFieldShape = RoundedCornerShape(12.dp)

@Composable
fun proxyFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedBorderColor = MaterialTheme.colorScheme.primary
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsCard(
    state: ProxyEditUiState,
    onIntent: (ProxyEditIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (state.protocol) {
                ProxyProtocol.SHADOWSOCKS -> {
                    DropdownField(
                        label = stringResource(R.string.proxy_field_method),
                        options = listOf(
                            "aes-256-gcm",
                            "aes-128-gcm",
                            "chacha20-ietf-poly1305",
                            "2022-blake3-aes-128-gcm",
                            "2022-blake3-aes-256-gcm",
                            "2022-blake3-chacha20-poly1305"
                        ),
                        selected = state.method,
                        onSelected = { onIntent(ProxyEditIntent.SetMethod(it)) }
                    )
                    PasswordField(
                        value = state.password,
                        error = state.errors[ProxyEditViewModel.KEY_PASSWORD],
                        onValueChange = { onIntent(ProxyEditIntent.SetPassword(it)) }
                    )
                }

                ProxyProtocol.VLESS -> {
                    UuidField(
                        value = state.uuid,
                        error = state.errors[ProxyEditViewModel.KEY_UUID],
                        onValueChange = { onIntent(ProxyEditIntent.SetUuid(it)) },
                        onGenerate = { onIntent(ProxyEditIntent.GenerateUuid) }
                    )
                    DropdownField(
                        label = stringResource(R.string.proxy_field_security),
                        options = listOf("reality", "tls", "none"),
                        selected = state.security,
                        onSelected = { onIntent(ProxyEditIntent.SetSecurity(it)) }
                    )
                    DropdownField(
                        label = stringResource(R.string.proxy_field_flow),
                        options = listOf("", "xtls-rprx-vision"),
                        selected = state.flow,
                        onSelected = { onIntent(ProxyEditIntent.SetFlow(it)) }
                    )
                }

                ProxyProtocol.VMESS -> {
                    UuidField(
                        value = state.uuid,
                        error = state.errors[ProxyEditViewModel.KEY_UUID],
                        onValueChange = { onIntent(ProxyEditIntent.SetUuid(it)) },
                        onGenerate = { onIntent(ProxyEditIntent.GenerateUuid) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.alterId,
                            onValueChange = { onIntent(ProxyEditIntent.SetAlterId(it)) },
                            label = { Text(stringResource(R.string.proxy_field_alter_id)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.4f)
                        )
                        DropdownField(
                            label = stringResource(R.string.proxy_field_security),
                            options = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none"),
                            selected = state.security,
                            onSelected = { onIntent(ProxyEditIntent.SetSecurity(it)) },
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                }

                ProxyProtocol.TROJAN -> {
                    PasswordField(
                        value = state.password,
                        error = state.errors[ProxyEditViewModel.KEY_PASSWORD],
                        onValueChange = { onIntent(ProxyEditIntent.SetPassword(it)) }
                    )
                }

                ProxyProtocol.HYSTERIA2 -> {
                    PasswordField(
                        value = state.password,
                        error = state.errors[ProxyEditViewModel.KEY_PASSWORD],
                        onValueChange = { onIntent(ProxyEditIntent.SetPassword(it)) }
                    )
                    DropdownField(
                        label = stringResource(R.string.proxy_field_obfs),
                        options = listOf("", "salamander"),
                        selected = state.obfs,
                        onSelected = { onIntent(ProxyEditIntent.SetObfs(it)) }
                    )
                    if (state.obfs.isNotEmpty()) {
                        OutlinedTextField(
                            value = state.obfsPassword,
                            onValueChange = { onIntent(ProxyEditIntent.SetObfsPassword(it)) },
                            label = { Text(stringResource(R.string.proxy_field_obfs_password)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                ProxyProtocol.TUIC -> {
                    UuidField(
                        value = state.uuid,
                        error = state.errors[ProxyEditViewModel.KEY_UUID],
                        onValueChange = { onIntent(ProxyEditIntent.SetUuid(it)) },
                        onGenerate = { onIntent(ProxyEditIntent.GenerateUuid) }
                    )
                    PasswordField(
                        value = state.password,
                        error = state.errors[ProxyEditViewModel.KEY_PASSWORD],
                        onValueChange = { onIntent(ProxyEditIntent.SetPassword(it)) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DropdownField(
                            label = stringResource(R.string.proxy_field_congestion_control),
                            options = listOf("bbr", "cubic", "new_reno"),
                            selected = state.congestionControl,
                            onSelected = { onIntent(ProxyEditIntent.SetCongestionControl(it)) },
                            modifier = Modifier.weight(1f)
                        )
                        DropdownField(
                            label = stringResource(R.string.proxy_field_udp_relay_mode),
                            options = listOf("native", "quic"),
                            selected = state.udpRelayMode,
                            onSelected = { onIntent(ProxyEditIntent.SetUdpRelayMode(it)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                ProxyProtocol.MASQUE -> {
                    OutlinedTextField(
                        value = state.masqueUser,
                        onValueChange = { onIntent(ProxyEditIntent.SetMasqueUser(it)) },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    PasswordField(
                        value = state.masquePass,
                        error = state.errors[ProxyEditViewModel.KEY_PASSWORD],
                        onValueChange = { onIntent(ProxyEditIntent.SetMasquePass(it)) }
                    )
                    OutlinedTextField(
                        value = state.masquePath,
                        onValueChange = { onIntent(ProxyEditIntent.SetMasquePath(it)) },
                        label = { Text("Path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ProxyProtocol.SOCKS5, ProxyProtocol.HTTP, ProxyProtocol.HTTPS -> {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = { onIntent(ProxyEditIntent.SetUsername(it)) },
                        label = { Text("Username (optional)") },
                        singleLine = true,
                        shape = ProxyFieldShape,
                        colors = proxyFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    PasswordField(
                        value = state.password,
                        error = state.errors[ProxyEditViewModel.KEY_PASSWORD],
                        onValueChange = { onIntent(ProxyEditIntent.SetPassword(it)) }
                    )
                }

                ProxyProtocol.SOCKS4 -> {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = { onIntent(ProxyEditIntent.SetUsername(it)) },
                        label = { Text("User ID (optional)") },
                        singleLine = true,
                        shape = ProxyFieldShape,
                        colors = proxyFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ProxyProtocol.SSH -> {
                    val usernameError = state.errors[ProxyEditViewModel.KEY_USERNAME]
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = { onIntent(ProxyEditIntent.SetUsername(it)) },
                        label = { Text("Username") },
                        isError = usernameError != null,
                        supportingText = usernameError?.let { msg -> { Text(msg) } },
                        singleLine = true,
                        shape = ProxyFieldShape,
                        colors = proxyFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    PasswordField(
                        value = state.password,
                        error = state.errors[ProxyEditViewModel.KEY_PASSWORD],
                        onValueChange = { onIntent(ProxyEditIntent.SetPassword(it)) }
                    )
                    OutlinedTextField(
                        value = state.privateKey,
                        onValueChange = { onIntent(ProxyEditIntent.SetPrivateKey(it)) },
                        label = { Text("Private Key PEM (optional)") },
                        minLines = 3,
                        maxLines = 5,
                        shape = ProxyFieldShape,
                        colors = proxyFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.privateKey.isNotBlank()) {
                        OutlinedTextField(
                            value = state.passphrase,
                            onValueChange = { onIntent(ProxyEditIntent.SetPassphrase(it)) },
                            label = { Text("Key Passphrase (optional)") },
                            singleLine = true,
                            shape = ProxyFieldShape,
                            colors = proxyFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.ifEmpty { "(none)" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            shape = ProxyFieldShape,
            colors = proxyFieldColors(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            options.forEach { option ->
                val isSelected = selected == option
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.ifEmpty { "(none)" },
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun PasswordField(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.proxy_field_password)) },
        shape = ProxyFieldShape,
        colors = proxyFieldColors(),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null
                )
            }
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun UuidField(
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.proxy_field_uuid)) },
        shape = ProxyFieldShape,
        colors = proxyFieldColors(),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        trailingIcon = {
            IconButton(onClick = onGenerate) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.proxy_field_generate_uuid)
                )
            }
        },
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}
