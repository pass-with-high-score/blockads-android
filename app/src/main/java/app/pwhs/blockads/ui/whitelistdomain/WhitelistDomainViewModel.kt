package app.pwhs.blockads.ui.whitelistdomain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.entities.WhitelistDomain
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WhitelistDomainViewModel(
    private val whitelistDomainDao: WhitelistDomainDao,
    application: Application
) : AndroidViewModel(application) {

    val whitelistDomains: StateFlow<List<WhitelistDomain>> = whitelistDomainDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun addDomain(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            if (cleanDomain.isNotBlank()) {
                val exists = whitelistDomainDao.exists(cleanDomain)
                if (exists == 0) {
                    whitelistDomainDao.insert(WhitelistDomain(domain = cleanDomain))
                    _events.toast(R.string.whitelist_domain_added, listOf(cleanDomain))
                    requestVpnRestart()
                } else {
                    _events.toast(R.string.filter_domain_already_whitelisted)
                }
            }
        }
    }

    fun removeDomain(domain: WhitelistDomain) {
        viewModelScope.launch {
            whitelistDomainDao.delete(domain)
            _events.toast(R.string.whitelist_domain_removed)
            requestVpnRestart()
        }
    }

    private fun requestVpnRestart() {
        AdBlockVpnService.requestRestart(getApplication<Application>().applicationContext)
    }
}
