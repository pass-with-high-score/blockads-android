package app.pwhs.blockads.ui.domainrules.data

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import app.pwhs.blockads.R

data class PresetRuleSpec(
    val type: String,
    val param: String,
    val policy: String,
    val option: String = ""
) {
    val rawDisplay: String
        get() = if (option.isNotEmpty()) "$type, $param, $policy, $option" else "$type, $param, $policy"
}

enum class RulePreset(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val isAllowPreset: Boolean,
    val rules: List<PresetRuleSpec>
) {
    BLOCK_QUIC(
        labelRes = R.string.rule_preset_block_quic,
        icon = Icons.Default.Block,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("AND", "((NETWORK,UDP),(DST-PORT,443))", "REJECT-DROP")
        )
    ),
    BLOCK_STUN(
        labelRes = R.string.rule_preset_block_stun,
        icon = Icons.Default.VisibilityOff,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("AND", "((NETWORK,UDP),(DST-PORT,3478/19302-19309))", "REJECT-DROP")
        )
    ),
    BLOCK_DOT(
        labelRes = R.string.rule_preset_block_dot,
        icon = Icons.Default.Dns,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("DST-PORT", "853", "REJECT")
        )
    ),
    BLOCK_LOOPBACK(
        labelRes = R.string.rule_preset_block_loopback,
        icon = Icons.Default.SyncDisabled,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("IP-CIDR", "127.0.0.0/8", "REJECT", "no-resolve"),
            PresetRuleSpec("IP-CIDR", "0.0.0.0/32", "REJECT", "no-resolve"),
            PresetRuleSpec("IP-CIDR6", "::1/128", "REJECT", "no-resolve"),
            PresetRuleSpec("IP-CIDR6", "::/128", "REJECT", "no-resolve")
        )
    ),
    BLOCK_DOH(
        labelRes = R.string.rule_preset_block_doh,
        icon = Icons.Default.Dns,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("HOST", "dns.google", "REJECT"),
            PresetRuleSpec("HOST", "cloudflare-dns.com", "REJECT"),
            PresetRuleSpec("HOST", "mozilla.cloudflare-dns.com", "REJECT"),
            PresetRuleSpec("HOST", "dns.quad9.net", "REJECT"),
            PresetRuleSpec("HOST", "dns.nextdns.io", "REJECT")
        )
    ),
    BLOCK_TELEMETRY(
        labelRes = R.string.rule_preset_block_telemetry,
        icon = Icons.Default.Analytics,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("HOST-KEYWORD", "telemetry", "REJECT"),
            PresetRuleSpec("HOST-KEYWORD", "adservice", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "doubleclick.net", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "googlesyndication.com", "REJECT")
        )
    ),
    BLOCK_SOCIAL_TRACKERS(
        labelRes = R.string.rule_preset_block_social_trackers,
        icon = Icons.Default.PublicOff,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("HOST-SUFFIX", "pixel.facebook.com", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "an.facebook.com", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "analytics.tiktok.com", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "ads-twitter.com", "REJECT")
        )
    ),
    BLOCK_OEM_TELEMETRY(
        labelRes = R.string.rule_preset_block_oem_telemetry,
        icon = Icons.Default.Devices,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("HOST-SUFFIX", "samsungcloudsolution.com", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "tracking.miui.com", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "metrics.icloud.com", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "events.data.microsoft.com", "REJECT")
        )
    ),
    BLOCK_CRYPTO_MINERS(
        labelRes = R.string.rule_preset_block_crypto_miners,
        icon = Icons.Default.CurrencyBitcoin,
        isAllowPreset = false,
        rules = listOf(
            PresetRuleSpec("HOST-KEYWORD", "coinhive", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "cryptoloot.pro", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "minero.cc", "REJECT"),
            PresetRuleSpec("HOST-SUFFIX", "webminepool.com", "REJECT")
        )
    ),
    ALLOW_LAN(
        labelRes = R.string.rule_preset_allow_lan,
        icon = Icons.Default.Wifi,
        isAllowPreset = true,
        rules = listOf(
            PresetRuleSpec("GEOSITE", "private", "DIRECT"),
            PresetRuleSpec("GEOIP", "LAN", "DIRECT", "no-resolve")
        )
    ),
    ALLOW_SYSTEM_PUSH(
        labelRes = R.string.rule_preset_allow_system_push,
        icon = Icons.Default.NotificationsActive,
        isAllowPreset = true,
        rules = listOf(
            PresetRuleSpec("GEOSITE", "apple", "DIRECT"),
            PresetRuleSpec("GEOSITE", "microsoft", "DIRECT"),
            PresetRuleSpec("HOST-SUFFIX", "mtalk.google.com", "DIRECT")
        )
    ),
    ALLOW_BITTORRENT(
        labelRes = R.string.rule_preset_allow_bittorrent,
        icon = Icons.Default.CloudDownload,
        isAllowPreset = true,
        rules = listOf(
            PresetRuleSpec("GEOSITE", "category-public-tracker", "DIRECT"),
            PresetRuleSpec("GEOSITE", "category-pt", "DIRECT"),
            PresetRuleSpec(
                "PROCESS-NAME-REGEX",
                "(?i)^(qbittorrent|transmission.*|deluge.*|aria2c|motrix|utorrent|bitcomet)(\\.exe)?$",
                "DIRECT"
            )
        )
    )
}
