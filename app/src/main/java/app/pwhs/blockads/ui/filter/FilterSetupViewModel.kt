package app.pwhs.blockads.ui.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.FilterList
import app.pwhs.blockads.data.FilterListDao
import app.pwhs.blockads.data.FilterListRepository
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

class FilterSetupViewModel(
    private val filterRepo: FilterListRepository,
    private val filterListDao: FilterListDao,
) : ViewModel() {

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isUpdatingFilter = MutableStateFlow(false)
    val isUpdatingFilter: StateFlow<Boolean> = _isUpdatingFilter.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            filterRepo.seedDefaultsIfNeeded()
        }
    }

    fun toggleFilterList(filter: FilterList) {
        viewModelScope.launch {
            filterListDao.setEnabled(filter.id, !filter.isEnabled)
        }
    }

    fun addFilterList(name: String, url: String) {
        viewModelScope.launch {
            filterListDao.insert(
                FilterList(name = name, url = url, isEnabled = true, isBuiltIn = false)
            )
            _events.toast(R.string.settings_add, listOf(": $name"))
        }
    }

    fun deleteFilterList(filter: FilterList) {
        if (filter.isBuiltIn) return
        viewModelScope.launch {
            filterListDao.delete(filter)
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
