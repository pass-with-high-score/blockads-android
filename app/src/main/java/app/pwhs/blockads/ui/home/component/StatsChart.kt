package app.pwhs.blockads.ui.home.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import ir.ehsannarmani.compose_charts.LineChart
import ir.ehsannarmani.compose_charts.models.DividerProperties
import ir.ehsannarmani.compose_charts.models.DrawStyle
import ir.ehsannarmani.compose_charts.models.GridProperties
import ir.ehsannarmani.compose_charts.models.HorizontalIndicatorProperties
import ir.ehsannarmani.compose_charts.models.LabelHelperProperties
import ir.ehsannarmani.compose_charts.models.LabelProperties
import ir.ehsannarmani.compose_charts.models.Line
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val hourFormat = ThreadLocal.withInitial {
    SimpleDateFormat("HH", Locale.getDefault())
}
private val dayFormat = ThreadLocal.withInitial {
    SimpleDateFormat("EEE", Locale.getDefault())
}

@Composable
fun StatsChart(
    stats: List<HourlyStat>,
    modifier: Modifier = Modifier
) {
    val textColor = TextSecondary
    val chartData = remember(stats) {
        val fmt = hourFormat.get()!!
        val labels = stats.map { fmt.format(Date(it.hour)) }
        val totalLine = Line(
            label = "Total",
            values = stats.map { it.total.toDouble() },
            color = SolidColor(AccentBlue),
            firstGradientFillColor = AccentBlue.copy(alpha = 0.3f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 2.dp),
            curvedEdges = true,
        )
        val blockedLine = Line(
            label = "Blocked",
            values = stats.map { it.blocked.toDouble() },
            color = SolidColor(DangerRed),
            firstGradientFillColor = DangerRed.copy(alpha = 0.2f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 2.dp),
            curvedEdges = true,
        )
        Triple(listOf(totalLine, blockedLine), labels, labels)
    }

    if (stats.isEmpty()) return

    LineChart(
        modifier = modifier.fillMaxSize(),
        data = chartData.first,
        curvedEdges = true,
        labelProperties = LabelProperties(
            enabled = true,
            textStyle = TextStyle(color = textColor, fontSize = 9.sp),
            labels = chartData.second,
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
        val labels = stats.map { fmt.format(Date(it.day)) }
        val totalLine = Line(
            label = "Total",
            values = stats.map { it.total.toDouble() },
            color = SolidColor(AccentBlue),
            firstGradientFillColor = AccentBlue.copy(alpha = 0.3f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 2.5.dp),
            curvedEdges = true,
        )
        val blockedLine = Line(
            label = "Blocked",
            values = stats.map { it.blocked.toDouble() },
            color = SolidColor(DangerRed),
            firstGradientFillColor = DangerRed.copy(alpha = 0.2f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 2.5.dp),
            curvedEdges = true,
        )
        Pair(listOf(totalLine, blockedLine), labels)
    }

    if (stats.isEmpty()) return

    LineChart(
        modifier = modifier.fillMaxSize(),
        data = chartData.first,
        curvedEdges = true,
        labelProperties = LabelProperties(
            enabled = true,
            textStyle = TextStyle(color = textColor, fontSize = 10.sp),
            labels = chartData.second,
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
        val labels = stats.map {
            if (it.week.contains("W")) "W${it.week.substringAfterLast("W")}" else it.week
        }
        val totalLine = Line(
            label = "Total",
            values = stats.map { it.total.toDouble() },
            color = SolidColor(AccentBlue),
            firstGradientFillColor = AccentBlue.copy(alpha = 0.3f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 2.5.dp),
            curvedEdges = true,
        )
        val blockedLine = Line(
            label = "Blocked",
            values = stats.map { it.blocked.toDouble() },
            color = SolidColor(DangerRed),
            firstGradientFillColor = DangerRed.copy(alpha = 0.2f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 2.5.dp),
            curvedEdges = true,
        )
        Pair(listOf(totalLine, blockedLine), labels)
    }

    if (stats.isEmpty()) return

    LineChart(
        modifier = modifier.fillMaxSize(),
        data = chartData.first,
        curvedEdges = true,
        labelProperties = LabelProperties(
            enabled = true,
            textStyle = TextStyle(color = textColor, fontSize = 10.sp),
            labels = chartData.second,
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
        val labels = stats.map {
            if (it.month.contains("-")) it.month.substringAfterLast("-") else it.month
        }
        val totalLine = Line(
            label = "Total",
            values = stats.map { it.total.toDouble() },
            color = SolidColor(AccentBlue),
            firstGradientFillColor = AccentBlue.copy(alpha = 0.3f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 2.5.dp),
            curvedEdges = true,
        )
        val blockedLine = Line(
            label = "Blocked",
            values = stats.map { it.blocked.toDouble() },
            color = SolidColor(DangerRed),
            firstGradientFillColor = DangerRed.copy(alpha = 0.2f),
            secondGradientFillColor = Color.Transparent,
            drawStyle = DrawStyle.Stroke(width = 2.5.dp),
            curvedEdges = true,
        )
        Pair(listOf(totalLine, blockedLine), labels)
    }

    if (stats.isEmpty()) return

    LineChart(
        modifier = modifier.fillMaxSize(),
        data = chartData.first,
        curvedEdges = true,
        labelProperties = LabelProperties(
            enabled = true,
            textStyle = TextStyle(color = textColor, fontSize = 9.sp),
            labels = chartData.second,
        ),
        indicatorProperties = HorizontalIndicatorProperties(enabled = false),
        gridProperties = GridProperties(enabled = false),
        dividerProperties = DividerProperties(enabled = false),
        labelHelperProperties = LabelHelperProperties(enabled = false),
        animationDelay = 0,
    )
}