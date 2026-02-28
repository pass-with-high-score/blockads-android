package app.pwhs.blockads.ui.filter

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FilterSetupViewModel(
    private val filterRepo: FilterListRepository,
    private val filterListDao: FilterListDao,
    private val application: Application,
) : ViewModel() {

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredFilterLists: StateFlow<List<FilterList>> = combine(
        filterLists, _searchQuery
    ) { lists, query ->
        if (query.isBlank()) lists
        else lists.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isUpdatingFilter = MutableStateFlow(false)
    val isUpdatingFilter: StateFlow<Boolean> = _isUpdatingFilter.asStateFlow()

    private val _isValidatingUrl = MutableStateFlow(false)
    val isValidatingUrl: StateFlow<Boolean> = _isValidatingUrl.asStateFlow()

    private val _filterAddedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val filterAddedEvent: SharedFlow<Unit> = _filterAddedEvent.asSharedFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            filterRepo.seedDefaultsIfNeeded()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFilterList(filter: FilterList) {
        viewModelScope.launch {
            filterListDao.setEnabled(filter.id, !filter.isEnabled)
            AdBlockVpnService.requestRestart(application.applicationContext)
        }
    }

    fun addFilterList(name: String, url: String) {
        viewModelScope.launch {
            val trimmedUrl = url.trim()

            // Check duplicate
            val existing = filterListDao.getByUrl(trimmedUrl)
            if (existing != null) {
                _events.toast(R.string.filter_error_duplicate_url)
                return@launch
            }

            // Validate URL content is a valid filter list
            _isValidatingUrl.value = true
            val validation = filterRepo.validateFilterUrl(trimmedUrl)
            _isValidatingUrl.value = false

            if (validation.isFailure) {
                _events.toast(R.string.filter_error_invalid_content)
                return@launch
            }

            filterListDao.insert(
                FilterList(name = name, url = trimmedUrl, isEnabled = true, isBuiltIn = false)
            )
            _events.toast(R.string.settings_add, listOf(": $name"))
            _filterAddedEvent.tryEmit(Unit)

            // Immediately load/update all filters to fetch content from the new URL
            filterRepo.loadAllEnabledFilters()
            AdBlockVpnService.requestRestart(application.applicationContext)
        }
    }

    fun deleteFilterList(filter: FilterList) {
        if (filter.isBuiltIn) return
        viewModelScope.launch {
            filterListDao.delete(filter)
            AdBlockVpnService.requestRestart(application.applicationContext)
        }
    }

    fun updateAllFilters() {
        viewModelScope.launch {
            _isUpdatingFilter.value = true
            val result = filterRepo.loadAllEnabledFilters()
            _isUpdatingFilter.value = false

            result.fold(
                onSuccess = { count ->
                    _events.toast(
                        R.string.filter_updated,
                        listOf(count)
                    )
                },
                onFailure = {
                    _events.toast(R.string.filter_update_failed)
                }
            )
        }
    }
}
