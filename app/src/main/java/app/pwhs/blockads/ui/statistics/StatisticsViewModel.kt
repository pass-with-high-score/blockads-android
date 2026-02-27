package app.pwhs.blockads.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.entities.AppStat
import app.pwhs.blockads.data.entities.DailyStat
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.data.entities.HourlyStat
import app.pwhs.blockads.data.entities.MonthlyStat
import app.pwhs.blockads.data.entities.TopBlockedDomain
import app.pwhs.blockads.data.entities.WeeklyStat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class StatisticsViewModel(
    dnsLogDao: DnsLogDao
) : ViewModel() {

    private val todayStart: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val totalCount: StateFlow<Int> = dnsLogDao.getTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val blockedCount: StateFlow<Int> = dnsLogDao.getBlockedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todayTotal: StateFlow<Int> = dnsLogDao.getTotalCountSince(todayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todayBlocked: StateFlow<Int> = dnsLogDao.getBlockedCountSince(todayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val securityBlockedCount: StateFlow<Int> = dnsLogDao.getBlockedCountByReason(
        FilterListRepository.BLOCK_REASON_SECURITY
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todaySecurityBlocked: StateFlow<Int> = dnsLogDao.getBlockedCountByReasonSince(
        FilterListRepository.BLOCK_REASON_SECURITY, todayStart
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val hourlyStats: StateFlow<List<HourlyStat>> = dnsLogDao.getHourlyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyStats: StateFlow<List<DailyStat>> = dnsLogDao.getDailyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklyStats: StateFlow<List<WeeklyStat>> = dnsLogDao.getWeeklyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val monthlyStats: StateFlow<List<MonthlyStat>> = dnsLogDao.getMonthlyStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topBlockedDomains: StateFlow<List<TopBlockedDomain>> = dnsLogDao.getTopBlockedDomains()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topApps: StateFlow<List<AppStat>> = dnsLogDao.getTopApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
