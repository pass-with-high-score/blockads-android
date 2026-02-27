package app.pwhs.blockads.ui.filter.detail

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
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
    private val filterRepo: FilterListRepository,
    private val application: Application,
) : ViewModel() {

    val filter: StateFlow<FilterList?> = filterListDao.getByIdFlow(filterId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _domainPreview = MutableStateFlow<List<String>>(emptyList())
    val domainPreview: StateFlow<List<String>> = _domainPreview.asStateFlow()

    private val _isLoadingDomains = MutableStateFlow(true)
    val isLoadingDomains: StateFlow<Boolean> = _isLoadingDomains.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        loadDomainPreview()
    }

    private fun loadDomainPreview() {
        viewModelScope.launch {
            _isLoadingDomains.value = true
            val f = filterListDao.getById(filterId)
            if (f != null) {
                _domainPreview.value = filterRepo.getDomainPreview(f, 200)
            }
            _isLoadingDomains.value = false
        }
    }

    fun toggleFilter() {
        viewModelScope.launch {
            val f = filter.value ?: return@launch
            filterListDao.setEnabled(f.id, !f.isEnabled)
            AdBlockVpnService.requestRestart(application.applicationContext)
        }
    }

    fun updateFilter() {
        viewModelScope.launch {
            val f = filter.value ?: return@launch
            _isUpdating.value = true
            val result = filterRepo.updateSingleFilter(f)
            _isUpdating.value = false

            result.fold(
                onSuccess = { count ->
                    _events.toast(R.string.filter_updated, listOf(count))
                    loadDomainPreview() // Reload preview after update
                },
                onFailure = {
                    _events.toast(R.string.filter_update_failed)
                }
            )
        }
    }

    fun deleteFilter() {
        viewModelScope.launch {
            val f = filter.value ?: return@launch
            if (!f.isBuiltIn) {
                filterListDao.delete(f)
                AdBlockVpnService.requestRestart(application.applicationContext)
            }
        }
    }
}
