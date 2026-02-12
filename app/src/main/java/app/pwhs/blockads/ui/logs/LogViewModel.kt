package app.pwhs.blockads.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.DnsLogDao
import app.pwhs.blockads.data.DnsLogEntry
import app.pwhs.blockads.data.WhitelistDomain
import app.pwhs.blockads.data.WhitelistDomainDao
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LogViewModel(
    private val dnsLogDao: DnsLogDao,
    private val whitelistDomainDao: WhitelistDomainDao,
) : ViewModel() {

    private val _showBlockedOnly = MutableStateFlow(false)
    val showBlockedOnly: StateFlow<Boolean> = _showBlockedOnly.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val logs: StateFlow<List<DnsLogEntry>> = _showBlockedOnly.flatMapLatest { blockedOnly ->
        if (blockedOnly) dnsLogDao.getBlockedOnly() else dnsLogDao.getAll()
    }.combine(_searchQuery) { logs, query ->
        if (query.isBlank()) logs
        else logs.filter {
            it.domain.contains(query.trim(), ignoreCase = true) ||
                    it.appName.contains(query.trim(), ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleFilter() {
        _showBlockedOnly.value = !_showBlockedOnly.value
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearLogs() {
        viewModelScope.launch {
            dnsLogDao.clearAll()
        }
    }

    fun addToWhitelist(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            val exists = whitelistDomainDao.exists(cleanDomain)
            if (exists == 0) {
                whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
                _events.toast(R.string.log_whitelisted, listOf(": $cleanDomain"))
            } else {
                _events.toast(R.string.log_already_whitelisted)
            }
        }
    }
}

