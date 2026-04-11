package app.pwhs.blockads.ui.filter.detail

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.repository.CustomFilterManager
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FilterDetailViewModel(
    private val filterId: Long,
    private val filterListDao: FilterListDao,
    private val dnsLogDao: DnsLogDao,
    private val filterRepo: FilterListRepository,
    private val application: Application,
    private val customFilterManager: CustomFilterManager
) : ViewModel() {

    val filter: StateFlow<FilterList?> = filterListDao.getByIdFlow(filterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val blockedCount: StateFlow<Int> = dnsLogDao.getBlockedCountByReason(filterId.toString())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _testDomainQuery = MutableStateFlow("")
    val testDomainQuery: StateFlow<String> = _testDomainQuery.asStateFlow()

    private val _testDomainResult = MutableStateFlow<Boolean?>(null)
    val testDomainResult: StateFlow<Boolean?> = _testDomainResult.asStateFlow()

    private val _isTestingDomain = MutableStateFlow(false)
    val isTestingDomain: StateFlow<Boolean> = _isTestingDomain.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _showEditDialog = MutableStateFlow(false)
    val showEditDialog: StateFlow<Boolean> = _showEditDialog.asStateFlow()

    private val _editName = MutableStateFlow("")
    val editName: StateFlow<String> = _editName.asStateFlow()

    private val _editUrl = MutableStateFlow("")
    val editUrl: StateFlow<String> = _editUrl.asStateFlow()

    private val _editError = MutableStateFlow("")
    val editError: StateFlow<String> = _editError.asStateFlow()

    private val _isSavingEdit = MutableStateFlow(false)
    val isSavingEdit: StateFlow<Boolean> = _isSavingEdit.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun setTestDomainQuery(query: String) {
        _testDomainQuery.value = query
        _testDomainResult.value = null
    }

    fun testDomain() {
        val domain = _testDomainQuery.value.trim()
        if (domain.isBlank()) return
        
        viewModelScope.launch {
            _isTestingDomain.value = true
            _testDomainResult.value = filterRepo.checkDomainInFilter(filterId, domain)
            _isTestingDomain.value = false
        }
    }

    fun toggleFilter() {
        viewModelScope.launch {
            val f = filter.value ?: return@launch
            filterListDao.setEnabled(f.id, !f.isEnabled)
            ServiceController.requestRestart(application.applicationContext)
        }
    }

    fun updateFilter() {
        viewModelScope.launch {
            val f = filter.value ?: return@launch
            _isUpdating.value = true
            
            // Re-compile custom filters via backend API, else just download built-in files normally
            val result = if (f.isBuiltIn) {
                filterRepo.updateSingleFilter(f)
            } else {
                // CustomFilterManager.updateCustomFilter returns Result<FilterList>
                customFilterManager.updateCustomFilter(f).map { it.ruleCount }
            }
            
            _isUpdating.value = false

            result.fold(
                onSuccess = { count ->
                    _events.toast(R.string.filter_updated, listOf(count))
                    
                    // Reload the filter engine if the binary files were re-downloaded
                    filterRepo.loadAllEnabledFilters()
                },
                onFailure = {
                    _events.toast(R.string.filter_update_failed)
                }
            )
        }
    }

    fun switchBuildMode() {
        viewModelScope.launch {
            val f = filter.value ?: return@launch
            if (f.isBuiltIn) return@launch

            val isCurrentlyLocal = f.trieUrl.startsWith("local://")

            if (isCurrentlyLocal) {
                // Switch to server: re-compile via backend API
                _isUpdating.value = true
                val result = customFilterManager.updateCustomFilter(
                    f.copy(trieUrl = "", bloomUrl = "")
                )
                _isUpdating.value = false

                result.fold(
                    onSuccess = { updated ->
                        _events.toast(R.string.filter_updated, listOf(updated.ruleCount))
                        filterRepo.loadAllEnabledFilters()
                        ServiceController.requestRestart(application.applicationContext)
                    },
                    onFailure = {
                        _events.toast(R.string.filter_update_failed)
                    }
                )
            } else {
                // Switch to local: enqueue WorkManager job
                customFilterManager.enqueueRecompileLocally(f)
                _events.toast(R.string.filter_compile_enqueued)
            }
        }
    }

    fun deleteFilter() {
        viewModelScope.launch {
            val f = filter.value ?: return@launch
            if (!f.isBuiltIn) {
                filterListDao.delete(f)
                ServiceController.requestRestart(application.applicationContext)
            }
        }
    }

    fun openEditDialog() {
        val f = filter.value ?: return
        if (f.isBuiltIn) return
        _editName.value = f.name
        _editUrl.value = f.originalUrl.ifEmpty { f.url }
        _editError.value = ""
        _showEditDialog.value = true
    }

    fun closeEditDialog() {
        _showEditDialog.value = false
    }

    fun setEditName(name: String) {
        _editName.value = name
    }

    fun setEditUrl(url: String) {
        _editUrl.value = url
    }

    fun saveEdit() {
        val f = filter.value ?: return
        val name = _editName.value.trim()
        val url = _editUrl.value.trim()

        if (name.isBlank()) {
            _editError.value = "Name cannot be empty"
            return
        }
        if (url.isBlank()) {
            _editError.value = "Domain/URL cannot be empty"
            return
        }

        viewModelScope.launch {
            _isSavingEdit.value = true
            _editError.value = ""

            val result = customFilterManager.editCustomFilter(f, name, url)

            _isSavingEdit.value = false

            result.fold(
                onSuccess = { updatedFilter ->
                    _showEditDialog.value = false
                    _events.toast(R.string.filter_updated, listOf(updatedFilter.ruleCount))

                    // Reload the filter engine if the URL changed (binaries re-downloaded/re-compiled)
                    if (url != f.originalUrl && url != f.url) {
                        filterRepo.loadAllEnabledFilters()
                        ServiceController.requestRestart(application.applicationContext)
                    }
                },
                onFailure = { error ->
                    _editError.value = error.message ?: "Failed to edit filter"
                }
            )
        }
    }
}
