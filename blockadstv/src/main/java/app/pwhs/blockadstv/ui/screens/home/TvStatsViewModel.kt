package app.pwhs.blockadstv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockadstv.data.dao.DnsLogDao
import app.pwhs.blockadstv.data.dao.FilterListDao
import app.pwhs.blockadstv.data.entities.AppStat
import app.pwhs.blockadstv.data.entities.BlockReasonStat
import app.pwhs.blockadstv.data.entities.CountryStat
import app.pwhs.blockadstv.data.entities.DailyStat
import app.pwhs.blockadstv.data.entities.MonthlyStat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

enum class StatsTimeRange(val label: String, val hours: Long) {
    HOURS_24("24h", 24L),
    DAYS_7("7d", 24L * 7),
    ALL("All", 0L),
}

enum class StatsTab(val label: String) {
    DAILY("Daily (7d)"),
    MONTHLY("Monthly"),
    REASONS("Block Reasons"),
    APPS("Top Apps"),
    COUNTRIES("Countries"),
}

class TvStatsViewModel(
    private val dnsLogDao: DnsLogDao,
    filterListDao: FilterListDao,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(StatsTab.DAILY)
    val selectedTab: StateFlow<StatsTab> = _selectedTab.asStateFlow()

    private val _timeRange = MutableStateFlow(StatsTimeRange.HOURS_24)
    val timeRange: StateFlow<StatsTimeRange> = _timeRange.asStateFlow()

    fun selectTab(tab: StatsTab) {
        _selectedTab.value = tab
    }

    fun selectTimeRange(range: StatsTimeRange) {
        _timeRange.value = range
    }

    val dailyStats: StateFlow<List<DailyStat>> = dnsLogDao.getDailyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val monthlyStats: StateFlow<List<MonthlyStat>> = dnsLogDao.getMonthlyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topApps: StateFlow<List<AppStat>> = dnsLogDao.getTopApps(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val countryStats: StateFlow<List<CountryStat>> = _timeRange.flatMapLatest { range ->
        if (range == StatsTimeRange.ALL) {
            dnsLogDao.getAllCountryStats()
        } else {
            val since = System.currentTimeMillis() - range.hours * 3_600_000L
            dnsLogDao.getCountryStatsSince(since)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val blockReasons: StateFlow<List<BlockReasonStat>> = combine(
        _timeRange.flatMapLatest { range ->
            if (range == StatsTimeRange.ALL) {
                dnsLogDao.getAllBlockReasonStats()
            } else {
                val since = System.currentTimeMillis() - range.hours * 3_600_000L
                dnsLogDao.getBlockReasonStatsSince(since)
            }
        },
        filterListDao.getAll(),
    ) { rawStats, filterLists ->
        val filterMap = filterLists.associateBy { it.id.toString() }
        val aggregated = mutableMapOf<String, Int>()

        for (raw in rawStats) {
            val parts = raw.blockedBy.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            for (part in parts) {
                aggregated[part] = (aggregated[part] ?: 0) + raw.count
            }
        }

        aggregated.map { (key, count) ->
            val filter = filterMap[key]
            if (filter != null) {
                BlockReasonStat(
                    reasonKey = key,
                    displayName = filter.name,
                    count = count,
                    filterId = filter.id,
                    category = filter.category,
                )
            } else {
                val (name, cat) = when (key.lowercase()) {
                    "custom", "custom_rule" -> "Custom Rules" to "CUSTOM"
                    "security" -> "Security Protection" to "SECURITY"
                    "firewall", "connection" -> "App Firewall" to "FIREWALL"
                    "upstream_dns" -> "Upstream DNS" to "DNS"
                    "filter_list", "ad" -> "Ad & Tracker Filter" to "AD"
                    else -> key to ""
                }
                BlockReasonStat(
                    reasonKey = key,
                    displayName = name,
                    count = count,
                    filterId = null,
                    category = cat,
                )
            }
        }.sortedByDescending { it.count }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
