package app.pwhs.blockads.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.entities.DnsLogEntry
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import app.pwhs.blockads.util.CustomRuleParser
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TimeRange(val millis: Long) {
    ALL(0L),
    HOUR_1(3_600_000L),
    HOUR_6(21_600_000L),
    HOUR_24(86_400_000L),
    DAY_7(604_800_000L)
}

class LogViewModel(
    private val dnsLogDao: DnsLogDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val filterListRepository: FilterListRepository,
) : ViewModel() {

    private val _showBlockedOnly = MutableStateFlow(false)
    val showBlockedOnly: StateFlow<Boolean> = _showBlockedOnly.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _timeRange = MutableStateFlow(TimeRange.ALL)
    val timeRange: StateFlow<TimeRange> = _timeRange.asStateFlow()

    private val _appFilter = MutableStateFlow("")
    val appFilter: StateFlow<String> = _appFilter.asStateFlow()

    private val _realTimeMode = MutableStateFlow(false)
    val realTimeMode: StateFlow<Boolean> = _realTimeMode.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val whitelistedDomains: StateFlow<Set<String>> = whitelistDomainDao.getAll()
        .map { list -> list.map { it.domain.lowercase() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val appNames: StateFlow<List<String>> = dnsLogDao.getDistinctAppNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val logs: StateFlow<List<DnsLogEntry>> = combine(
        _showBlockedOnly,
        _timeRange
    ) { blockedOnly, range -> Pair(blockedOnly, range) }
        .flatMapLatest { (blockedOnly, range) ->
            val since = if (range == TimeRange.ALL) 0L
            else System.currentTimeMillis() - range.millis
            when {
                blockedOnly && since > 0 -> dnsLogDao.getBlockedOnlySince(since)
                blockedOnly -> dnsLogDao.getBlockedOnly()
                since > 0 -> dnsLogDao.getAllSince(since)
                else -> dnsLogDao.getAll()
            }
        }
        .combine(_searchQuery) { logs, query ->
            if (query.isBlank()) logs
            else logs.filter {
                it.domain.contains(query.trim(), ignoreCase = true) ||
                        it.appName.contains(query.trim(), ignoreCase = true)
            }
        }
        .combine(_appFilter) { logs, app ->
            if (app.isBlank()) logs
            else logs.filter { it.appName.equals(app, ignoreCase = true) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleFilter() {
        _showBlockedOnly.value = !_showBlockedOnly.value
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setTimeRange(range: TimeRange) {
        _timeRange.value = range
    }

    fun setAppFilter(app: String) {
        _appFilter.value = app
    }

    fun toggleRealTimeMode() {
        _realTimeMode.value = !_realTimeMode.value
    }

    fun toggleSelectionMode() {
        _selectionMode.value = !_selectionMode.value
        if (!_selectionMode.value) {
            _selectedIds.value = emptySet()
        }
    }

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _selectedIds.value = current
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
                filterListRepository.loadWhitelist()
                _events.toast(R.string.log_whitelisted, listOf(": $cleanDomain"))
            } else {
                _events.toast(R.string.log_already_whitelisted)
            }
        }
    }

    fun addToCustomBlockRules(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            val rule = CustomRuleParser.parseRule(CustomRuleParser.formatBlockRule(cleanDomain))
            if (rule != null) {
                customDnsRuleDao.insert(rule)
                filterListRepository.loadCustomRules()
                _events.toast(R.string.rule_added)
            }
        }
    }

    fun unblockDomain(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            // Remove from custom block rules
            customDnsRuleDao.deleteBlockRuleByDomain(cleanDomain)
            // Add to whitelist to ensure it's not blocked by filter lists
            val exists = whitelistDomainDao.exists(cleanDomain)
            if (exists == 0) {
                whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
            }
            filterListRepository.loadCustomRules()
            filterListRepository.loadWhitelist()
            _events.toast(R.string.log_domain_unblocked, listOf(cleanDomain))
        }
    }

    fun bulkBlock() {
        viewModelScope.launch {
            val currentLogs = logs.value
            val ids = _selectedIds.value
            val domains = currentLogs.filter { ids.contains(it.id) }.map { it.domain }.distinct()
            domains.forEach { domain ->
                val cleanDomain = domain.trim().lowercase()
                val rule =
                    CustomRuleParser.parseRule(CustomRuleParser.formatBlockRule(cleanDomain))
                if (rule != null) {
                    customDnsRuleDao.insert(rule)
                }
            }
            filterListRepository.loadCustomRules()
            _selectedIds.value = emptySet()
            _selectionMode.value = false
            _events.toast(R.string.rule_added)
        }
    }

    fun bulkWhitelist() {
        viewModelScope.launch {
            val currentLogs = logs.value
            val ids = _selectedIds.value
            val domains = currentLogs.filter { ids.contains(it.id) }.map { it.domain }.distinct()
            domains.forEach { domain ->
                val cleanDomain = domain.trim().lowercase()
                val exists = whitelistDomainDao.exists(cleanDomain)
                if (exists == 0) {
                    whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
                }
            }
            filterListRepository.loadWhitelist()
            _selectedIds.value = emptySet()
            _selectionMode.value = false
            _events.toast(R.string.log_whitelisted, listOf(""))
        }
    }
}

