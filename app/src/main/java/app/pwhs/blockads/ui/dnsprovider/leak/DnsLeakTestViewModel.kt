package app.pwhs.blockads.ui.dnsprovider.leak

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.repository.DnsLeakRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.system.measureTimeMillis
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService

class DnsLeakTestViewModel(
    private val dnsLeakRepository: DnsLeakRepository,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val latencyProvider: (suspend () -> Long?)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DnsLeakTestUiState())
    val uiState: StateFlow<DnsLeakTestUiState> = _uiState.asStateFlow()

    private val _uiEffect = MutableSharedFlow<DnsLeakTestUiEffect>()
    val uiEffect: SharedFlow<DnsLeakTestUiEffect> = _uiEffect.asSharedFlow()

    fun onIntent(intent: DnsLeakTestUiIntent) {
        when (intent) {
            is DnsLeakTestUiIntent.StartTest -> startTest()
            is DnsLeakTestUiIntent.Reset -> reset()
        }
    }

    private fun startTest() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    progressStage = "init",
                    errorMessage = null,
                    result = null
                )
            }

            val latencyDeferred = async(ioDispatcher) {
                latencyProvider?.invoke() ?: runCatching {
                    val adapter = AdBlockVpnService.activeTunnelAdapter ?: RootProxyService.activeTunnelAdapter
                    measureTimeMillis {
                        if (adapter != null) {
                            adapter.lookupIP("one.one.one.one")
                        } else {
                            InetAddress.getByName("one.one.one.one")
                        }
                    }
                }.getOrNull()
            }

            val dnssecDeferred = async(ioDispatcher) {
                testDnssec()
            }

            val reservedIps = withContext(ioDispatcher) {
                runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { intf ->
                        intf.inetAddresses.toList().filter { addr ->
                            !addr.isLoopbackAddress && (
                                addr.isSiteLocalAddress ||
                                addr.isLinkLocalAddress ||
                                addr.hostAddress?.startsWith("100.64.") == true ||
                                addr.hostAddress?.startsWith("fd") == true ||
                                addr.hostAddress?.startsWith("192.168.") == true ||
                                addr.hostAddress?.startsWith("10.") == true ||
                                addr.hostAddress?.startsWith("172.") == true
                            )
                        }.mapNotNull { it.hostAddress?.substringBefore("%") }
                    }?.distinct() ?: emptyList()
                }.getOrDefault(emptyList())
            }

            val result = dnsLeakRepository.runLeakTest { stage ->
                _uiState.update { it.copy(progressStage = stage) }
            }

            val latency = latencyDeferred.await()
            val dnssecStatus = dnssecDeferred.await()

            result.fold(
                onSuccess = { response ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            progressStage = "done",
                            result = response,
                            latencyMs = latency,
                            dnssecStatus = dnssecStatus,
                            reservedIps = reservedIps
                        )
                    }
                },
                onFailure = { error ->
                    val msg = error.localizedMessage ?: "Failed to complete DNS leak test"
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            progressStage = "",
                            errorMessage = msg
                        )
                    }
                    _uiEffect.emit(DnsLeakTestUiEffect.ShowSnackbar(msg))
                }
            )
        }
    }

    private fun reset() {
        _uiState.update { DnsLeakTestUiState() }
    }

    private suspend fun testDnssec(): DnssecSecurityStatus = withContext(ioDispatcher) {
        val cid = (1..8).map { "0123456789abcdef".random() }.joinToString("")
        val algs = listOf(
            Triple("alg13", "ECDSA P-256", "RFC 6605"),
            Triple("alg14", "ECDSA P-384", "RFC 6605"),
            Triple("alg15", "Ed25519", "RFC 8080"),
            Triple("alg18", "ML-DSA-44", "Post-Quantum")
        )

        val adapter = AdBlockVpnService.activeTunnelAdapter ?: RootProxyService.activeTunnelAdapter

        val results = algs.map { (algKey, name, desc) ->
            val validResolves = runCatching {
                if (adapter != null) {
                    adapter.lookupIP("$cid.test-$algKey.dnscheck.tools") != null
                } else {
                    InetAddress.getByName("$cid.test-$algKey.dnscheck.tools")
                    true
                }
            }.getOrDefault(false)

            val badsigBlocked = runCatching {
                if (adapter != null) {
                    adapter.lookupIP("$cid-badsig.test-$algKey.dnscheck.tools") == null
                } else {
                    InetAddress.getByName("$cid-badsig.test-$algKey.dnscheck.tools")
                    false
                }
            }.getOrElse { true }

            DnssecAlgorithmResult(
                algorithm = name,
                name = desc,
                isValidating = badsigBlocked,
                isSupported = validResolves
            )
        }

        val standardFailedBlocked = runCatching {
            if (adapter != null) {
                adapter.lookupIP("dnssec-failed.org") == null
            } else {
                InetAddress.getByName("dnssec-failed.org")
                false
            }
        }.getOrElse { true }

        val overallValidating = standardFailedBlocked || results.any { it.isValidating && it.isSupported }

        DnssecSecurityStatus(
            isValidating = overallValidating,
            algorithms = results
        )
    }
}
