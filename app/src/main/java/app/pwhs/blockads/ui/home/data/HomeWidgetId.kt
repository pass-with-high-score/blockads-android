package app.pwhs.blockads.ui.home.data

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector
import app.pwhs.blockads.R

enum class HomeWidgetId(
    val id: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    QUERY_STATS("query_stats", R.string.total_queries, Icons.Default.QueryStats),
    BLOCK_RATE("block_rate", R.string.home_block_rate, Icons.Default.Shield),
    NETWORK_SPEED("network_speed", R.string.home_downloaded, Icons.Default.SwapVert),
    DATA_AND_UPTIME("data_and_uptime", R.string.home_data_saved, Icons.Default.DataSaverOn),
    ACTIVITY_CHART("activity_chart", R.string.home_chart_24h, Icons.Default.BarChart),
    TOP_BLOCKED("top_blocked", R.string.home_top_blocked, Icons.Default.Block),
    RECENT_ACTIVITY("recent_activity", R.string.home_recent_blocked, Icons.Default.History);

    companion object {
        val DEFAULT_ORDER: List<HomeWidgetId> = entries.toList()

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
