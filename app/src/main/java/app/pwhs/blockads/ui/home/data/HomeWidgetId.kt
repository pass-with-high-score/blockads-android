package app.pwhs.blockads.ui.home.data

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector
import app.pwhs.blockads.R

enum class HomeWidgetId(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
) {
    QUERY_STATS(
        id = "query_stats",
        titleRes = R.string.total_queries,
        descriptionRes = R.string.home_widget_query_stats_desc,
        icon = Icons.Default.QueryStats,
    ),
    BLOCK_RATE(
        id = "block_rate",
        titleRes = R.string.home_block_rate,
        descriptionRes = R.string.home_widget_block_rate_desc,
        icon = Icons.Default.Shield,
    ),
    NETWORK_SPEED(
        id = "network_speed",
        titleRes = R.string.home_downloaded,
        descriptionRes = R.string.home_widget_network_speed_desc,
        icon = Icons.Default.SwapVert,
    ),
    DATA_AND_UPTIME(
        id = "data_and_uptime",
        titleRes = R.string.home_data_saved,
        descriptionRes = R.string.home_widget_data_and_uptime_desc,
        icon = Icons.Default.DataSaverOn,
    ),
    ACTIVITY_CHART(
        id = "activity_chart",
        titleRes = R.string.home_chart_24h,
        descriptionRes = R.string.home_widget_activity_chart_desc,
        icon = Icons.Default.BarChart,
    ),
    TOP_BLOCKED(
        id = "top_blocked",
        titleRes = R.string.home_top_blocked,
        descriptionRes = R.string.home_widget_top_blocked_desc,
        icon = Icons.Default.Block,
    ),
    RECENT_ACTIVITY(
        id = "recent_activity",
        titleRes = R.string.home_recent_blocked,
        descriptionRes = R.string.home_widget_recent_activity_desc,
        icon = Icons.Default.History,
    ),
    QUICK_ACTIONS(
        id = "quick_actions",
        titleRes = R.string.home_widget_quick_actions,
        descriptionRes = R.string.home_widget_quick_actions_desc,
        icon = Icons.Default.Bolt,
    ),
    ACTIVE_DNS(
        id = "active_dns",
        titleRes = R.string.home_widget_active_dns,
        descriptionRes = R.string.home_widget_active_dns_desc,
        icon = Icons.Default.Dns,
    ),
    TOP_APPS(
        id = "top_apps",
        titleRes = R.string.home_widget_top_apps,
        descriptionRes = R.string.home_widget_top_apps_desc,
        icon = Icons.Default.Apps,
    ),
    SECURITY_SHIELD(
        id = "security_shield",
        titleRes = R.string.home_widget_security_shield,
        descriptionRes = R.string.home_widget_security_shield_desc,
        icon = Icons.Default.Security,
    ),
    PROXY_VPN_STATUS(
        id = "proxy_vpn_status",
        titleRes = R.string.home_widget_proxy_vpn,
        descriptionRes = R.string.home_widget_proxy_vpn_desc,
        icon = Icons.Default.VpnKey,
    ),
    BLOCK_REASONS(
        id = "block_reasons",
        titleRes = R.string.home_widget_block_reasons,
        descriptionRes = R.string.home_widget_block_reasons_desc,
        icon = Icons.Default.PieChart,
    ),
    TOP_COUNTRIES(
        id = "top_countries",
        titleRes = R.string.home_widget_top_countries,
        descriptionRes = R.string.home_widget_top_countries_desc,
        icon = Icons.Default.Public,
    );

    companion object {
        val DEFAULT_ORDER: List<HomeWidgetId> = listOf(
            QUERY_STATS,
            BLOCK_RATE,
            NETWORK_SPEED,
            DATA_AND_UPTIME,
            ACTIVITY_CHART,
            TOP_BLOCKED,
            RECENT_ACTIVITY,
        )

        fun fromSerialized(raw: String?): List<HomeWidgetId> {
            if (raw == null) return DEFAULT_ORDER
            if (raw.isEmpty()) return emptyList()
            val byId = entries.associateBy { it.id }
            val parsed = raw.split(',')
                .mapNotNull { token -> byId[token.trim()] }
                .distinct()
            return parsed.ifEmpty { DEFAULT_ORDER }
        }

        fun serialize(list: List<HomeWidgetId>): String =
            list.distinct().joinToString(",") { it.id }
    }
}
