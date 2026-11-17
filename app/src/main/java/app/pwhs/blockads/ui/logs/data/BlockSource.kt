package app.pwhs.blockads.ui.logs.data

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.SecurityOrange
import app.pwhs.blockads.ui.theme.UpstreamDnsPurple
import app.pwhs.blockads.ui.theme.WhitelistAmber

enum class BlockSource(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val color: Color
) {
    FILTER_LIST(
        labelRes = R.string.block_reason_filter_list,
        icon = Icons.Default.FilterList,
        color = DangerRed
    ),
    FIREWALL(
        labelRes = R.string.block_reason_firewall,
        icon = Icons.Default.LocalFireDepartment,
        color = AccentBlue
    ),
    CUSTOM_RULE(
        labelRes = R.string.stats_block_reason_custom,
        icon = Icons.Default.Tune,
        color = WhitelistAmber
    ),
    SECURITY(
        labelRes = R.string.stats_block_reason_security,
        icon = Icons.Default.Security,
        color = SecurityOrange
    ),
    UPSTREAM_DNS(
        labelRes = R.string.block_reason_upstream_dns,
        icon = Icons.Default.Dns,
        color = UpstreamDnsPurple
    );

    companion object {
        fun resolveSources(blockedBy: String, securityFilterIds: Set<String> = emptySet()): Set<BlockSource> {
            val upper = blockedBy.trim().uppercase()
            if (upper.isEmpty()) return setOf(FILTER_LIST)

            val parts = upper.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return setOf(FILTER_LIST)

            val sources = mutableSetOf<BlockSource>()
            for (part in parts) {
                when {
                    part == "FIREWALL" || part == "CONNECTION" -> sources.add(FIREWALL)
                    part == "CUSTOM_RULE" || part == "CUSTOM" -> sources.add(CUSTOM_RULE)
                    part == "SECURITY" || part in securityFilterIds -> sources.add(SECURITY)
                    part == "UPSTREAM_DNS" -> sources.add(UPSTREAM_DNS)
                    part == "FILTER_LIST" || part == "AD" -> sources.add(FILTER_LIST)
                    else -> {
                        if (part in securityFilterIds) {
                            sources.add(SECURITY)
                        } else {
                            sources.add(FILTER_LIST)
                        }
                    }
                }
            }
            return if (sources.isEmpty()) setOf(FILTER_LIST) else sources
        }

        fun matches(
            entry: DnsLogEntry,
            selectedSources: Set<BlockSource>,
            securityFilterIds: Set<String> = emptySet()
        ): Boolean {
            if (!entry.isBlocked) return true
            if (selectedSources.isEmpty()) return false
            if (selectedSources.size == entries.size) return true
            val entrySources = resolveSources(entry.blockedBy, securityFilterIds)
            return entrySources.any { it in selectedSources }
        }
    }
}
