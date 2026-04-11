package app.pwhs.blockads.ui.filter

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.FilterList
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FilterSetupViewModel(
    private val filterRepo: FilterListRepository,
    private val filterListDao: FilterListDao,
    private val customFilterManager: CustomFilterManager,
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

    private val _isAddingCustomFilter = MutableStateFlow(false)
    val isAddingCustomFilter: StateFlow<Boolean> = _isAddingCustomFilter.asStateFlow()

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
            // Recalculate the active domain count immediately even if VPN is stopped
            filterRepo.loadAllEnabledFilters()
            ServiceController.requestRestart(application.applicationContext)
        }
    }

    fun addFilterList(name: String, url: String, buildLocally: Boolean = false) {
        viewModelScope.launch {
            val trimmedUrl = url.trim()

            // Check duplicate
            val existing = filterListDao.getByUrl(trimmedUrl)
            if (existing != null) {
                _events.toast(R.string.filter_error_duplicate_url)
                return@launch
            }

            if (buildLocally) {
                // Enqueue WorkManager job — returns immediately, compiles in background
                customFilterManager.enqueueLocalCompile(trimmedUrl, name.trim())
                _events.toast(R.string.filter_compile_enqueued)
                _filterAddedEvent.tryEmit(Unit)
            } else {
                _isAddingCustomFilter.value = true
                val result = customFilterManager.addCustomFilter(trimmedUrl, name.trim())
                _isAddingCustomFilter.value = false

                result.fold(
                    onSuccess = { _ ->
                        _events.toast(R.string.settings_add, listOf(": $name"))
                        _filterAddedEvent.tryEmit(Unit)

                        // Reload all filters so the new custom filter is active
                        filterRepo.loadAllEnabledFilters()
                        ServiceController.requestRestart(application.applicationContext)
                    },
                    onFailure = { _ ->
                        _events.toast(R.string.filter_update_failed)
                    }
                )
            }
        }
    }

    fun deleteFilterList(filter: FilterList) {
        if (filter.isBuiltIn) return
        viewModelScope.launch {
            // Deletes the DB entity AND the local binary files (.trie, .bloom, .css)
            customFilterManager.deleteCustomFilter(filter)
            // Reload the filter engine without this filter
            filterRepo.loadAllEnabledFilters()
            ServiceController.requestRestart(application.applicationContext)
        }
    }

    fun updateAllFilters() {
        viewModelScope.launch {
            _isUpdatingFilter.value = true
            
            var totalCount = 0

            // 1. Update remote built-in filters
            val result = filterRepo.forceUpdateAllEnabledFilters()
            result.onSuccess { count -> totalCount += count }

            // 2. Update all enabled custom filters
            val customFilters = filterListDao.getAllNonBuiltIn()
            for (filter in customFilters) {
                if (filter.isEnabled) {
                    // Recompile via backend, download new binaries, and update rule count
                    val customResult = customFilterManager.updateCustomFilter(filter)
                    customResult.onSuccess { updatedFilter ->
                        totalCount += updatedFilter.ruleCount
                    }
                }
            }

            // Always reload engine in case any custom filters updated their binaries
            filterRepo.loadAllEnabledFilters()
            
            _isUpdatingFilter.value = false

            if (result.isSuccess || totalCount > 0) {
                _events.toast(
                    R.string.filter_updated,
                    listOf(totalCount)
                )
            } else {
                result.onFailure {
                    _events.toast(R.string.filter_update_failed)
                }
            }
        }
    }
}
