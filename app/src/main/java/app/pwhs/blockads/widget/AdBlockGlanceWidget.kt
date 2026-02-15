package app.pwhs.blockads.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.DnsLogDao
import app.pwhs.blockads.data.HourlyStat
import app.pwhs.blockads.data.TopBlockedDomain
import app.pwhs.blockads.data.WidgetStats
import app.pwhs.blockads.service.AdBlockVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin
import java.util.Calendar
import java.util.Locale

class AdBlockGlanceWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "AdBlockGlanceWidget"
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val MAX_BAR_HEIGHT_DP = 32

        val SMALL = DpSize(110.dp, 48.dp)
        val MEDIUM = DpSize(250.dp, 120.dp)
        val LARGE = DpSize(250.dp, 250.dp)

        private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestUpdate(context: Context) {
            updateScope.launch {
                try {
                    AdBlockGlanceWidget().updateAll(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating Glance widget", e)
                }
            }
        }
    }

    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL, MEDIUM, LARGE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val isRunning = AdBlockVpnService.isRunning

        // Load data from DB
        var allStats = WidgetStats(0, 0)
        var todayStats = WidgetStats(0, 0)
        var hourlyStats = emptyList<HourlyStat>()
        var topDomains = emptyList<TopBlockedDomain>()

        try {
            val dnsLogDao: DnsLogDao = getKoin().get()
            allStats = dnsLogDao.getWidgetStats()

            val todayStart = getTodayStartMillis()
            todayStats = dnsLogDao.getWidgetStatsSince(todayStart)

            val oneDayAgo = System.currentTimeMillis() - MILLIS_PER_DAY
            hourlyStats = dnsLogDao.getHourlyStatsForWidget(oneDayAgo)

            topDomains = dnsLogDao.getTopBlockedDomainsForWidget(5)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading widget data", e)
        }

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                when {
                    size.height >= LARGE.height && size.width >= LARGE.width ->
                        LargeWidget(isRunning, allStats, todayStats, hourlyStats, topDomains)

                    size.height >= MEDIUM.height && size.width >= MEDIUM.width ->
                        MediumWidget(isRunning, allStats, todayStats, hourlyStats)

                    else ->
                        SmallWidget(isRunning, todayStats)
                }
            }
        }
    }

    private fun getTodayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ─── Small Widget (2×1): Toggle + Blocked today ────────────────────

    @Composable
    private fun SmallWidget(isRunning: Boolean, todayStats: WidgetStats) {
        val context = LocalContext.current
        val toggleIntent = Intent(context, WidgetToggleReceiver::class.java).apply {
            action = WidgetToggleReceiver.ACTION_TOGGLE_VPN
        }

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(Color.White)
                .padding(8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Toggle button
            Image(
                provider = ImageProvider(R.drawable.ic_power),
                contentDescription = context.getString(R.string.widget_toggle_description),
                modifier = GlanceModifier
                    .size(40.dp)
                    .cornerRadius(20.dp)
                    .background(if (isRunning) colorGreen else colorGray)
                    .clickable(actionSendBroadcast(toggleIntent))
            )

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Status + blocked today
            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(
                        if (isRunning) R.string.status_protected else R.string.status_unprotected
                    ),
                    style = TextStyle(
                        color = if (isRunning) colorGreen else colorTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                Text(
                    text = context.getString(
                        R.string.widget_blocked_today,
                        formatCount(todayStats.blocked)
                    ),
                    style = TextStyle(
                        color = colorTextSecondary,
                        fontSize = 10.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }

    // ─── Medium Widget (4×2): Toggle + Stats + Mini chart ──────────────

    @Composable
    private fun MediumWidget(
        isRunning: Boolean,
        allStats: WidgetStats,
        todayStats: WidgetStats,
        hourlyStats: List<HourlyStat>
    ) {
        val context = LocalContext.current
        val toggleIntent = Intent(context, WidgetToggleReceiver::class.java).apply {
            action = WidgetToggleReceiver.ACTION_TOGGLE_VPN
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(Color.White)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            // Header row
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(
                        if (isRunning) R.drawable.ic_shield_on else R.drawable.ic_shield_off
                    ),
                    contentDescription = context.getString(R.string.app_name),
                    modifier = GlanceModifier.size(22.dp)
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = context.getString(R.string.app_name),
                        style = TextStyle(
                            color = colorTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = context.getString(
                            if (isRunning) R.string.status_protected
                            else R.string.status_unprotected
                        ),
                        style = TextStyle(
                            color = if (isRunning) colorGreen else colorTextSecondary,
                            fontSize = 11.sp
                        ),
                        maxLines = 1
                    )
                }
                Image(
                    provider = ImageProvider(R.drawable.ic_power),
                    contentDescription = context.getString(R.string.widget_toggle_description),
                    modifier = GlanceModifier
                        .size(40.dp)
                        .cornerRadius(20.dp)
                        .background(if (isRunning) colorGreen else colorGray)
                        .clickable(actionSendBroadcast(toggleIntent))
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // Stats row
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatColumn(
                    value = formatCount(todayStats.blocked),
                    label = context.getString(R.string.widget_blocked_label),
                    color = colorGreen,
                    modifier = GlanceModifier.defaultWeight()
                )
                VerticalDivider()
                StatColumn(
                    value = formatCount(todayStats.total),
                    label = context.getString(R.string.widget_total_label),
                    color = colorBlue,
                    modifier = GlanceModifier.defaultWeight()
                )
                VerticalDivider()
                StatColumn(
                    value = formatBlockRate(todayStats),
                    label = context.getString(R.string.home_block_rate),
                    color = colorOrange,
                    modifier = GlanceModifier.defaultWeight()
                )
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            // Mini bar chart
            MiniBarChart(
                hourlyStats = hourlyStats,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight()
            )
        }
    }

    // ─── Large Widget (4×4): Full dashboard ────────────────────────────

    @Composable
    private fun LargeWidget(
        isRunning: Boolean,
        allStats: WidgetStats,
        todayStats: WidgetStats,
        hourlyStats: List<HourlyStat>,
        topDomains: List<TopBlockedDomain>
    ) {
        val context = LocalContext.current
        val toggleIntent = Intent(context, WidgetToggleReceiver::class.java).apply {
            action = WidgetToggleReceiver.ACTION_TOGGLE_VPN
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(Color.White)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            // Header row
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(
                        if (isRunning) R.drawable.ic_shield_on else R.drawable.ic_shield_off
                    ),
                    contentDescription = context.getString(R.string.app_name),
                    modifier = GlanceModifier.size(22.dp)
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = context.getString(R.string.app_name),
                        style = TextStyle(
                            color = colorTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = context.getString(
                            if (isRunning) R.string.status_protected
                            else R.string.status_unprotected
                        ),
                        style = TextStyle(
                            color = if (isRunning) colorGreen else colorTextSecondary,
                            fontSize = 11.sp
                        ),
                        maxLines = 1
                    )
                }
                Image(
                    provider = ImageProvider(R.drawable.ic_power),
                    contentDescription = context.getString(R.string.widget_toggle_description),
                    modifier = GlanceModifier
                        .size(44.dp)
                        .cornerRadius(22.dp)
                        .background(if (isRunning) colorGreen else colorGray)
                        .clickable(actionSendBroadcast(toggleIntent))
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // Today stats row
            Text(
                text = context.getString(R.string.widget_today_stats),
                style = TextStyle(
                    color = colorTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatColumn(
                    value = formatCount(todayStats.blocked),
                    label = context.getString(R.string.widget_blocked_label),
                    color = colorGreen,
                    modifier = GlanceModifier.defaultWeight()
                )
                VerticalDivider()
                StatColumn(
                    value = formatCount(todayStats.total),
                    label = context.getString(R.string.widget_total_label),
                    color = colorBlue,
                    modifier = GlanceModifier.defaultWeight()
                )
                VerticalDivider()
                StatColumn(
                    value = formatBlockRate(todayStats),
                    label = context.getString(R.string.home_block_rate),
                    color = colorOrange,
                    modifier = GlanceModifier.defaultWeight()
                )
            }

            Spacer(modifier = GlanceModifier.height(6.dp))

            // All-time stats row
            Text(
                text = context.getString(R.string.widget_alltime_stats),
                style = TextStyle(
                    color = colorTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatColumn(
                    value = formatCount(allStats.blocked),
                    label = context.getString(R.string.widget_blocked_label),
                    color = colorGreen,
                    modifier = GlanceModifier.defaultWeight()
                )
                VerticalDivider()
                StatColumn(
                    value = formatCount(allStats.total),
                    label = context.getString(R.string.widget_total_label),
                    color = colorBlue,
                    modifier = GlanceModifier.defaultWeight()
                )
                VerticalDivider()
                StatColumn(
                    value = formatBlockRate(allStats),
                    label = context.getString(R.string.home_block_rate),
                    color = colorOrange,
                    modifier = GlanceModifier.defaultWeight()
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // 24h Activity chart
            Text(
                text = context.getString(R.string.widget_activity_24h),
                style = TextStyle(
                    color = colorTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))

            MiniBarChart(
                hourlyStats = hourlyStats,
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(48.dp)
            )

            Spacer(modifier = GlanceModifier.height(8.dp))

            // Top blocked domains
            Text(
                text = context.getString(R.string.widget_top_blocked),
                style = TextStyle(
                    color = colorTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))

            if (topDomains.isEmpty()) {
                Text(
                    text = context.getString(R.string.widget_no_blocked_domains),
                    style = TextStyle(
                        color = colorTextSecondary,
                        fontSize = 10.sp
                    )
                )
            } else {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    topDomains.take(5).forEach { domain ->
                        DomainRow(domain)
                    }
                }
            }
        }
    }

    // ─── Shared Composables ────────────────────────────────────────────

    @Composable
    private fun StatColumn(
        value: String,
        label: String,
        color: ColorProvider,
        modifier: GlanceModifier
    ) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = TextStyle(
                    color = color,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Text(
                text = label,
                style = TextStyle(
                    color = colorTextSecondary,
                    fontSize = 10.sp
                ),
                maxLines = 1
            )
        }
    }

    @Composable
    private fun VerticalDivider() {
        Box(
            modifier = GlanceModifier
                .width(1.dp)
                .height(32.dp)
                .background(colorDivider)
        ) {}
    }

    @Composable
    private fun MiniBarChart(
        hourlyStats: List<HourlyStat>,
        modifier: GlanceModifier
    ) {
        if (hourlyStats.isEmpty()) {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = LocalContext.current.getString(R.string.widget_no_data),
                    style = TextStyle(
                        color = colorTextSecondary,
                        fontSize = 10.sp
                    )
                )
            }
            return
        }

        val maxBlocked = hourlyStats.maxOfOrNull { it.blocked } ?: 1
        val maxValue = maxOf(maxBlocked, 1)

        Row(
            modifier = modifier.cornerRadius(8.dp).background(colorChartBg),
            verticalAlignment = Alignment.Bottom
        ) {
            hourlyStats.takeLast(12).forEach { stat ->
                val fraction = stat.blocked.toFloat() / maxValue
                val barHeight = maxOf((fraction * MAX_BAR_HEIGHT_DP).toInt(), 2)
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .padding(horizontal = 1.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = GlanceModifier
                            .width(6.dp)
                            .height(barHeight.dp)
                            .cornerRadius(3.dp)
                            .background(colorGreen)
                    ) {}
                    Spacer(modifier = GlanceModifier.height(1.dp))
                }
            }
        }
    }

    @Composable
    private fun DomainRow(domain: TopBlockedDomain) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = domain.domain,
                style = TextStyle(
                    color = colorTextPrimary,
                    fontSize = 10.sp
                ),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = formatCount(domain.count),
                style = TextStyle(
                    color = colorGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                ),
                maxLines = 1
            )
        }
    }

    // ─── Formatting helpers ────────────────────────────────────────────

    private fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", count / 1_000_000f)
            count >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", count / 1_000f)
            else -> count.toString()
        }
    }

    private fun formatBlockRate(stats: WidgetStats): String {
        return if (stats.total > 0) {
            String.format(Locale.US, "%.1f%%", stats.blocked.toFloat() / stats.total * 100)
        } else {
            "0%"
        }
    }
}

// Color constants for the widget
private val colorGreen = ColorProvider(
    day = Color(0xFF00E676),
    night = Color(0xFF00C853)
)

private val colorGray = ColorProvider(
    day = Color(0xFFBDBDBD),
    night = Color(0xFF757575)
)

private val colorBlue = ColorProvider(
    day = Color(0xFF2196F3),
    night = Color(0xFF90CAF9)
)

private val colorOrange = ColorProvider(
    day = Color(0xFFFF9800),
    night = Color(0xFFFFB74D)
)

private val colorTextPrimary = ColorProvider(
    day = Color(0xFF212121),
    night = Color(0xFFE0E0E0)
)

private val colorTextSecondary = ColorProvider(
    day = Color(0xFF757575),
    night = Color(0xFFB0B0B0)
)

private val colorDivider = ColorProvider(
    day = Color(0xFFE0E0E0),
    night = Color(0xFF3A3A3A)
)

private val colorChartBg = ColorProvider(
    day = Color(0xFFEEEEEE),
    night = Color(0xFF1E1E1E)
)