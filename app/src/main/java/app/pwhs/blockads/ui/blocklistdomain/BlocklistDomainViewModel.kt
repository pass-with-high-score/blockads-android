package app.pwhs.blockads.ui.blocklistdomain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BlocklistDomainViewModel(
    private val customDnsRuleDao: CustomDnsRuleDao,
    application: Application
) : AndroidViewModel(application) {

    val blocklistDomains: StateFlow<List<CustomDnsRule>> = customDnsRuleDao.getAllFlow()
        .map { rules -> rules.filter { it.ruleType == RuleType.BLOCK } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun addDomain(domain: String) {
        viewModelScope.launch {
            val cleanDomain = domain.trim().lowercase()
            if (cleanDomain.isNotBlank()) {
                // Check for duplicates
                val allRules = customDnsRuleDao.getAll()
                val exists = allRules.any {
                    it.ruleType == RuleType.BLOCK && it.domain.equals(cleanDomain, ignoreCase = true)
                }
                if (!exists) {
                    customDnsRuleDao.insert(
                        CustomDnsRule(
                            rule = "||$cleanDomain^",
                            ruleType = RuleType.BLOCK,
                            domain = cleanDomain
                        )
                    )
                    _events.toast(R.string.blocklist_domain_added, listOf(cleanDomain))
                    requestVpnRestart()
                } else {
                    _events.toast(R.string.blocklist_domain_already_exists)
                }
            }
        }
    }

    fun removeDomain(rule: CustomDnsRule) {
        viewModelScope.launch {
            customDnsRuleDao.delete(rule)
            _events.toast(R.string.blocklist_domain_removed)
            requestVpnRestart()
        }
    }

    private fun requestVpnRestart() {
        AdBlockVpnService.requestRestart(getApplication<Application>().applicationContext)
    }
}
