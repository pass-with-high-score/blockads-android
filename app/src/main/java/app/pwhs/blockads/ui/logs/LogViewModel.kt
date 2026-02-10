package app.pwhs.blockads.ui.logs

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.DnsLogDao
import app.pwhs.blockads.data.DnsLogEntry
import app.pwhs.blockads.data.WhitelistDomain
import app.pwhs.blockads.data.WhitelistDomainDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LogViewModel(
    private val dnsLogDao: DnsLogDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val context: Context,
) : ViewModel() {

    private val _showBlockedOnly = MutableStateFlow(false)
    val showBlockedOnly: StateFlow<Boolean> = _showBlockedOnly.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val logs: StateFlow<List<DnsLogEntry>> = _showBlockedOnly.flatMapLatest { blockedOnly ->
        if (blockedOnly) dnsLogDao.getBlockedOnly() else dnsLogDao.getAll()
    }.combine(_searchQuery) { logs, query ->
        if (query.isBlank()) logs
        else logs.filter { it.domain.contains(query.trim(), ignoreCase = true) }
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
                Toast.makeText(context, "Whitelisted: $cleanDomain", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Already whitelisted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

