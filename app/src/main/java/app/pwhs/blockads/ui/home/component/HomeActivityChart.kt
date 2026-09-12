package app.pwhs.blockads.ui.home.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.DailyStat
import app.pwhs.blockads.data.entities.HourlyStat
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.TextSecondary

@Composable
fun HomeActivityChart(
    hourlyStats: List<HourlyStat>,
    dailyStats: List<DailyStat>,
    modifier: Modifier = Modifier
) {
    if (hourlyStats.isEmpty() && dailyStats.isEmpty()) return

    var selectedChartTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier) {
        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedChartTab == 0,
                onClick = { selectedChartTab = 0 },
                label = {
                    Text(
                        text = stringResource(R.string.home_chart_24h),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentBlue.copy(alpha = 0.2f),
                    selectedLabelColor = AccentBlue
                )
            )
            FilterChip(
                selected = selectedChartTab == 1,
                onClick = { selectedChartTab = 1 },
                label = {
                    Text(
                        text = stringResource(R.string.home_chart_7d),
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentBlue.copy(alpha = 0.2f),
                    selectedLabelColor = AccentBlue
                )
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            when (selectedChartTab) {
                0 -> {
                    if (hourlyStats.isNotEmpty()) {
                        StatsChart(
                            stats = hourlyStats,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .padding(16.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.home_chart_no_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                1 -> {
                    if (dailyStats.isNotEmpty()) {
                        DailyStatsChart(
                            stats = dailyStats,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .padding(16.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.home_chart_no_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
