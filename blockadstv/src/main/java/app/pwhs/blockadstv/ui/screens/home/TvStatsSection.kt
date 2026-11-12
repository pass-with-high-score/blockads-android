package app.pwhs.blockadstv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pwhs.blockadstv.data.entities.AppStat
import app.pwhs.blockadstv.data.entities.BlockReasonStat
import app.pwhs.blockadstv.data.entities.CountryStat
import app.pwhs.blockadstv.data.entities.DailyStat
import app.pwhs.blockadstv.data.entities.MonthlyStat
import app.pwhs.blockadstv.ui.theme.AccentBlue
import app.pwhs.blockadstv.ui.theme.DangerRed
import app.pwhs.blockadstv.ui.theme.NeonGreen
import app.pwhs.blockadstv.ui.theme.TextSecondary
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvStatsSection(
    modifier: Modifier = Modifier,
    viewModel: TvStatsViewModel = koinViewModel(),
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val timeRange by viewModel.timeRange.collectAsStateWithLifecycle()
    val dailyStats by viewModel.dailyStats.collectAsStateWithLifecycle()
    val monthlyStats by viewModel.monthlyStats.collectAsStateWithLifecycle()
    val topApps by viewModel.topApps.collectAsStateWithLifecycle()
    val blockReasons by viewModel.blockReasons.collectAsStateWithLifecycle()
    val countryStats by viewModel.countryStats.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Detailed Statistics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (selectedTab == StatsTab.REASONS || selectedTab == StatsTab.COUNTRIES) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatsTimeRange.entries.forEach { range ->
                            TvStatsChip(
                                label = range.label,
                                selected = timeRange == range,
                                onClick = { viewModel.selectTimeRange(range) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsTab.entries.forEach { tab ->
                    TvStatsChip(
                        label = tab.label,
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTab) {
                StatsTab.DAILY -> DailyStatsPanel(dailyStats)
                StatsTab.MONTHLY -> MonthlyStatsPanel(monthlyStats)
                StatsTab.REASONS -> BlockReasonsPanel(blockReasons)
                StatsTab.APPS -> TopAppsPanel(topApps)
                StatsTab.COUNTRIES -> CountryStatsPanel(countryStats)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvStatsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> NeonGreen.copy(alpha = 0.2f)
                    isFocused -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> NeonGreen
                    selected -> NeonGreen.copy(alpha = 0.7f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected || isFocused) NeonGreen else TextSecondary,
        )
    }
}

@Composable
private fun DailyStatsPanel(stats: List<DailyStat>) {
    if (stats.isEmpty()) {
        EmptyStatsHint("No daily DNS query data yet")
        return
    }
    val formatter = remember { SimpleDateFormat("EEE dd/MM", Locale.getDefault()) }
    val maxTotal = stats.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.takeLast(7).forEach { item ->
            StatBarRow(
                title = formatter.format(Date(item.day)),
                subtitle = "${item.blocked} blocked / ${item.total} total",
                primaryFraction = item.total.toFloat() / maxTotal,
                secondaryFraction = item.blocked.toFloat() / maxTotal,
            )
        }
    }
}

@Composable
private fun MonthlyStatsPanel(stats: List<MonthlyStat>) {
    if (stats.isEmpty()) {
        EmptyStatsHint("No monthly DNS query data yet")
        return
    }
    val maxTotal = stats.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.takeLast(6).forEach { item ->
            StatBarRow(
                title = item.month,
                subtitle = "${item.blocked} blocked / ${item.total} total",
                primaryFraction = item.total.toFloat() / maxTotal,
                secondaryFraction = item.blocked.toFloat() / maxTotal,
            )
        }
    }
}

@Composable
private fun BlockReasonsPanel(reasons: List<BlockReasonStat>) {
    if (reasons.isEmpty()) {
        EmptyStatsHint("No blocked queries in selected range")
        return
    }
    val maxCount = reasons.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reasons.take(8).forEach { item ->
            val label = if (item.category.isNotBlank()) {
                "${item.displayName} (${item.category})"
            } else {
                item.displayName
            }
            StatBarRow(
                title = label,
                subtitle = "${item.count} blocked",
                primaryFraction = item.count.toFloat() / maxCount,
                secondaryFraction = item.count.toFloat() / maxCount,
            )
        }
    }
}

@Composable
private fun TopAppsPanel(apps: List<AppStat>) {
    if (apps.isEmpty()) {
        EmptyStatsHint("No per-app query data yet")
        return
    }
    val maxTotal = apps.maxOfOrNull { it.totalQueries }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.take(8).forEach { item ->
            StatBarRow(
                title = item.appName.ifBlank { item.packageName },
                subtitle = "${item.blockedQueries} blocked / ${item.totalQueries} total",
                primaryFraction = item.totalQueries.toFloat() / maxTotal,
                secondaryFraction = item.blockedQueries.toFloat() / maxTotal,
            )
        }
    }
}

@Composable
private fun CountryStatsPanel(countries: List<CountryStat>) {
    if (countries.isEmpty()) {
        EmptyStatsHint("No destination country data in selected range")
        return
    }
    val maxCount = countries.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        countries.take(8).forEach { item ->
            val displayCountry = remember(item.countryCode) {
                val name = Locale.Builder().setRegion(item.countryCode).build().displayCountry
                if (name.isNotBlank() && !name.equals(item.countryCode, ignoreCase = true)) {
                    "${item.countryCode} · $name"
                } else {
                    item.countryCode
                }
            }
            StatBarRow(
                title = displayCountry,
                subtitle = "${item.count} queries",
                primaryFraction = item.count.toFloat() / maxCount,
                secondaryFraction = 0f,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatBarRow(
    title: String,
    subtitle: String,
    primaryFraction: Float,
    secondaryFraction: Float,
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable { /* D-pad focusable stat row */ }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (isFocused) NeonGreen else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(primaryFraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AccentBlue.copy(alpha = 0.7f)),
            )
            if (secondaryFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(secondaryFraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(DangerRed),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyStatsHint(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
