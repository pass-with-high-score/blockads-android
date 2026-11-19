package app.pwhs.blockads.ui.home.data

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.DnsProtocol

@Immutable
data class ActiveDnsInfo(
    val providerName: String = "",
    val protocol: DnsProtocol = DnsProtocol.PLAIN,
    val endpoint: String = "",
    val isEncrypted: Boolean = false,
    val isCustom: Boolean = false,
    @DrawableRes val iconRes: Int? = null,
)

@Immutable
data class SecurityShieldInfo(
    val enabledSecurityFilters: Int = 0,
    val httpsFilteringEnabled: Boolean = false,
    val firewallEnabled: Boolean = false,
    val privateDnsWarning: Boolean = false,
)

@Immutable
data class ProxyTunnelInfo(
    val routingMode: String = AppPreferences.ROUTING_MODE_DIRECT,
    val activeProxyType: String = AppPreferences.PROXY_TYPE_WIREGUARD,
    val profileName: String? = null,
    val endpoint: String? = null,
    val protocolLabel: String? = null,
    val tailscaleEnabled: Boolean = false,
    val isConnected: Boolean = false,
)
