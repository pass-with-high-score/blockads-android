package app.pwhs.blockads.ui.home.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.data.DailyStat
import app.pwhs.blockads.data.HourlyStat
import app.pwhs.blockads.data.MonthlyStat
import app.pwhs.blockads.data.WeeklyStat
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary
import ir.ehsannarmani.compose_charts.ColumnChart
import ir.ehsannarmani.compose_charts.models.BarProperties
import ir.ehsannarmani.compose_charts.models.Bars
import ir.ehsannarmani.compose_charts.models.DividerProperties
import ir.ehsannarmani.compose_charts.models.GridProperties
import ir.ehsannarmani.compose_charts.models.HorizontalIndicatorProperties
import ir.ehsannarmani.compose_charts.models.LabelHelperProperties
import ir.ehsannarmani.compose_charts.models.LabelProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hourFormat = ThreadLocal.withInitial {
    SimpleDateFormat("HH", Locale.getDefault())
}
private val dayFormat = ThreadLocal.withInitial {
    SimpleDateFormat("EEE", Locale.getDefault())
}
private val weekFormat = ThreadLocal.withInitial {
    SimpleDateFormat("MM/dd", Locale.getDefault())
}
private val monthFormat = ThreadLocal.withInitial {
    SimpleDateFormat("MMM", Locale.getDefault())
}

@Composable
fun StatsChart(
    stats: List<HourlyStat>,
    modifier: Modifier = Modifier
) {
    val textColor = TextSecondary
    val chartData = remember(stats) {
        val fmt = hourFormat.get()!!
        stats.map { stat ->
            Bars(
                label = fmt.format(Date(stat.hour)),
                values = listOf(
                    Bars.Data(
                        label = "Total",
                        value = stat.total.toDouble(),
                        color = SolidColor(AccentBlue.copy(alpha = 0.5f)),
                    ),
                    Bars.Data(
                        label = "Blocked",
                        value = stat.blocked.toDouble(),
                        color = SolidColor(DangerRed.copy(alpha = 0.8f)),
                    )
                )
            )
        }
    }

    if (chartData.isEmpty()) return

    ColumnChart(
        modifier = modifier.fillMaxSize(),
        data = chartData,
        barProperties = BarProperties(
            cornerRadius = Bars.Data.Radius.Rectangle(topLeft = 4.dp, topRight = 4.dp),
            spacing = 1.dp,
            thickness = 8.dp,
        ),
        labelProperties = LabelProperties(
            enabled = true,
            textStyle = TextStyle(color = textColor, fontSize = 9.sp),
        ),
        indicatorProperties = HorizontalIndicatorProperties(enabled = false),
        gridProperties = GridProperties(enabled = false),
        dividerProperties = DividerProperties(enabled = false),
        labelHelperProperties = LabelHelperProperties(enabled = false),
        animationDelay = 0,
    )
}

@Composable
fun DailyStatsChart(
    stats: List<DailyStat>,
    modifier: Modifier = Modifier
) {
    val textColor = TextSecondary
    val chartData = remember(stats) {
        val fmt = dayFormat.get()!!
        stats.map { stat ->
            Bars(
                label = fmt.format(Date(stat.day)),
                values = listOf(
                    Bars.Data(
                        label = "Total",
                        value = stat.total.toDouble(),
                        color = SolidColor(AccentBlue.copy(alpha = 0.5f)),
                    ),
                    Bars.Data(
                        label = "Blocked",
                        value = stat.blocked.toDouble(),
                        color = SolidColor(DangerRed.copy(alpha = 0.8f)),
                    )
                )
            )
        }
    }

    if (chartData.isEmpty()) return

    ColumnChart(
        modifier = modifier.fillMaxSize(),
        data = chartData,
        barProperties = BarProperties(
            cornerRadius = Bars.Data.Radius.Rectangle(topLeft = 4.dp, topRight = 4.dp),
            spacing = 2.dp,
            thickness = 20.dp,
        ),
        labelProperties = LabelProperties(
            enabled = true,
            textStyle = TextStyle(color = textColor, fontSize = 10.sp),
        ),
        indicatorProperties = HorizontalIndicatorProperties(enabled = false),
        gridProperties = GridProperties(enabled = false),
        dividerProperties = DividerProperties(enabled = false),
        labelHelperProperties = LabelHelperProperties(enabled = false),
        animationDelay = 0,
    )
}

@Composable
fun WeeklyStatsChart(
    stats: List<WeeklyStat>,
    modifier: Modifier = Modifier
) {
    val textColor = TextSecondary
    val chartData = remember(stats) {
        val fmt = weekFormat.get()!!
        stats.map { stat ->
            Bars(
                label = fmt.format(Date(stat.week)),
                values = listOf(
                    Bars.Data(
                        label = "Total",
                        value = stat.total.toDouble(),
                        color = SolidColor(AccentBlue.copy(alpha = 0.5f)),
                    ),
                    Bars.Data(
                        label = "Blocked",
                        value = stat.blocked.toDouble(),
                        color = SolidColor(DangerRed.copy(alpha = 0.8f)),
                    )
                )
            )
        }
    }

    if (chartData.isEmpty()) return

    ColumnChart(
        modifier = modifier.fillMaxSize(),
        data = chartData,
        barProperties = BarProperties(
            cornerRadius = Bars.Data.Radius.Rectangle(topLeft = 4.dp, topRight = 4.dp),
            spacing = 2.dp,
            thickness = 24.dp,
        ),
        labelProperties = LabelProperties(
            enabled = true,
            textStyle = TextStyle(color = textColor, fontSize = 10.sp),
        ),
        indicatorProperties = HorizontalIndicatorProperties(enabled = false),
        gridProperties = GridProperties(enabled = false),
        dividerProperties = DividerProperties(enabled = false),
        labelHelperProperties = LabelHelperProperties(enabled = false),
        animationDelay = 0,
    )
}

@Composable
fun MonthlyStatsChart(
    stats: List<MonthlyStat>,
    modifier: Modifier = Modifier
) {
    val textColor = TextSecondary
    val chartData = remember(stats) {
        val fmt = monthFormat.get()!!
        stats.map { stat ->
            Bars(
                label = fmt.format(Date(stat.month)),
                values = listOf(
                    Bars.Data(
                        label = "Total",
                        value = stat.total.toDouble(),
                        color = SolidColor(AccentBlue.copy(alpha = 0.5f)),
                    ),
                    Bars.Data(
                        label = "Blocked",
                        value = stat.blocked.toDouble(),
                        color = SolidColor(DangerRed.copy(alpha = 0.8f)),
                    )
                )
            )
        }
    }

    if (chartData.isEmpty()) return

    ColumnChart(
        modifier = modifier.fillMaxSize(),
        data = chartData,
        barProperties = BarProperties(
            cornerRadius = Bars.Data.Radius.Rectangle(topLeft = 4.dp, topRight = 4.dp),
            spacing = 2.dp,
            thickness = 20.dp,
        ),
        labelProperties = LabelProperties(
            enabled = true,
            textStyle = TextStyle(color = textColor, fontSize = 9.sp),
        ),
        indicatorProperties = HorizontalIndicatorProperties(enabled = false),
        gridProperties = GridProperties(enabled = false),
        dividerProperties = DividerProperties(enabled = false),
        labelHelperProperties = LabelHelperProperties(enabled = false),
        animationDelay = 0,
    )
}