package app.pwhs.blockads.ui.wireguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardEditScreen(
    profileId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WireGuardEditViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(profileId) { viewModel.load(profileId) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WireGuardEditViewModel.EditEvent.Saved -> {
                    snackbarHostState.showSnackbar("Saved '${event.name}'")
                    onNavigateBack()
                }
                is WireGuardEditViewModel.EditEvent.Failed ->
                    snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Edit profile") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }) { Text("Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                return@Box
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ProfileNameField(state.name, errors[WireGuardEditViewModel.FIELD_NAME], viewModel::setName) }
                item { SectionHeader("Interface") }
                item { InterfaceCard(state, errors, viewModel) }
                item { SectionHeader("Peers") }
                items(state.peers, key = { it.rowId }) { peer ->
                    PeerCard(
                        peer = peer,
                        errors = errors,
                        canDelete = state.peers.size > 1,
                        onUpdate = { transform -> viewModel.updatePeer(peer.rowId, transform) },
                        onDelete = { viewModel.removePeer(peer.rowId) },
                    )
                }
                item {
                    FilledTonalButton(
                        onClick = { viewModel.addPeer() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add peer")
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ProfileNameField(name: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        label = { Text("Name") },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InterfaceCard(
    state: WireGuardEditState,
    errors: WireGuardEditErrors,
    vm: WireGuardEditViewModel,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        FormColumn {
            FieldText(
                value = state.privateKey,
                label = "Private Key",
                error = errors[WireGuardEditViewModel.FIELD_PRIVATE_KEY],
                onChange = vm::setPrivateKey,
            )
            FieldText(
                value = state.addresses,
                label = "Addresses (comma-separated CIDR)",
                placeholder = "10.0.0.2/32, fd00::2/128",
                error = errors[WireGuardEditViewModel.FIELD_ADDRESSES],
                onChange = vm::setAddresses,
            )
            FieldText(
                value = state.listenPort,
                label = "Listen Port (optional)",
                error = errors[WireGuardEditViewModel.FIELD_LISTEN_PORT],
                keyboard = KeyboardType.Number,
                onChange = vm::setListenPort,
            )
            FieldText(
                value = state.dns,
                label = "DNS (comma-separated IPs)",
                placeholder = "1.1.1.1, 1.0.0.1",
                error = errors[WireGuardEditViewModel.FIELD_DNS],
                onChange = vm::setDns,
            )
        }
    }
}

@Composable
private fun PeerCard(
    peer: PeerFormState,
    errors: WireGuardEditErrors,
    canDelete: Boolean,
    onUpdate: ((PeerFormState) -> PeerFormState) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        FormColumn {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Peer",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove peer",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            FieldText(
                value = peer.publicKey,
                label = "Public Key",
                error = errors["peer.${peer.rowId}.publicKey"],
                onChange = { v -> onUpdate { it.copy(publicKey = v) } },
            )
            FieldText(
                value = peer.presharedKey,
                label = "Preshared Key (optional)",
                error = errors["peer.${peer.rowId}.presharedKey"],
                onChange = { v -> onUpdate { it.copy(presharedKey = v) } },
            )
            FieldText(
                value = peer.endpoint,
                label = "Endpoint",
                placeholder = "vpn.example.com:51820",
                error = errors["peer.${peer.rowId}.endpoint"],
                onChange = { v -> onUpdate { it.copy(endpoint = v) } },
            )
            FieldText(
                value = peer.allowedIPs,
                label = "Allowed IPs (comma-separated CIDR)",
                placeholder = "0.0.0.0/0, ::/0",
                error = errors["peer.${peer.rowId}.allowedIPs"],
                onChange = { v -> onUpdate { it.copy(allowedIPs = v) } },
            )
            FieldText(
                value = peer.persistentKeepalive,
                label = "Persistent Keepalive (seconds, optional)",
                error = errors["peer.${peer.rowId}.persistentKeepalive"],
                keyboard = KeyboardType.Number,
                onChange = { v -> onUpdate { it.copy(persistentKeepalive = v) } },
            )
        }
    }
}

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun FieldText(
    value: String,
    label: String,
    error: String? = null,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = keyboard == KeyboardType.Number,
        modifier = Modifier.fillMaxWidth(),
    )
}
