package app.pwhs.blockadstv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockadstv.data.dao.DnsLogDao
import app.pwhs.blockadstv.data.datastore.TvPreferences
import app.pwhs.blockadstv.service.TvVpnService
import app.pwhs.blockadstv.service.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

class TvHomeViewModel(
    private val dnsLogDao: DnsLogDao,
    private val tvPrefs: TvPreferences,
) : ViewModel() {

    val vpnState: StateFlow<VpnState> = TvVpnService.state

    val vpnEnabled: StateFlow<Boolean> = TvVpnService.state
        .map { it == VpnState.RUNNING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val vpnConnecting: StateFlow<Boolean> = TvVpnService.state
        .map { it == VpnState.STARTING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private fun startOfDayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    val blockedCount: StateFlow<Int> = dnsLogDao.getBlockedCountSince(startOfDayMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalCount: StateFlow<Int> = dnsLogDao.getTotalCountSince(startOfDayMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val securityThreats: StateFlow<Int> = dnsLogDao.getSecurityBlockedCountSince(startOfDayMillis())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val protectionUptimeMs: StateFlow<Long> = flow {
        while (true) {
            val ts = TvVpnService.startTimestamp
            if (ts > 0 && TvVpnService.isRunning) {
                emit(System.currentTimeMillis() - ts)
            } else {
                emit(0L)
            }
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
}
