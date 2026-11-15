package app.pwhs.blockads.ui.filter

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.datastore.prefs.FilterPreferences
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.repository.CustomFilterManager
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.event.toast
import app.pwhs.blockads.BuildConfig
import kotlinx.coroutines.flow.map
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
    private val appPreferences: AppPreferences? = null
) : ViewModel() {

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .map { lists ->
            if (BuildConfig.IS_STORE) {
                lists.filter { !it.isBuiltIn }
            } else {
                lists
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val starredFilterUrls: StateFlow<Set<String>> = appPreferences?.filter?.starredFilterUrls
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
        ?: MutableStateFlow<Set<String>>(emptySet()).asStateFlow()

    val filterBuildMode: StateFlow<String> = appPreferences?.filterBuildMode
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.BUILD_MODE_ONLINE)
        ?: MutableStateFlow(AppPreferences.BUILD_MODE_ONLINE).asStateFlow()

    val customFilterTags: StateFlow<Set<String>> = (appPreferences?.customFilterTags
        ?: MutableStateFlow(FilterPreferences.DEFAULT_CUSTOM_TAGS))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FilterPreferences.DEFAULT_CUSTOM_TAGS)

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    fun selectTag(tag: String?) {
        _selectedTag.value = tag
    }

    fun createTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            appPreferences?.addCustomFilterTag(trimmed)
            _selectedTag.value = trimmed
        }
    }

    fun deleteTag(tag: String) {
        viewModelScope.launch {
            appPreferences?.removeCustomFilterTag(tag)
            if (_selectedTag.value == tag) {
                _selectedTag.value = null
            }
        }
    }

    private val _pendingDeleteFilterIds = MutableStateFlow<Set<Long>>(emptySet())

    private val _undoDeleteEvent = MutableSharedFlow<FilterList>(extraBufferCapacity = 1)
    val undoDeleteEvent: SharedFlow<FilterList> = _undoDeleteEvent.asSharedFlow()

    val filteredFilterLists: StateFlow<List<FilterList>> = combine(
        filterLists, _searchQuery, _pendingDeleteFilterIds
    ) { lists, query, pendingIds ->
        val visibleLists = if (pendingIds.isEmpty()) lists else lists.filterNot { pendingIds.contains(it.id) }
        if (query.isBlank()) visibleLists
        else visibleLists.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isUpdatingFilter = MutableStateFlow(false)
    val isUpdatingFilter: StateFlow<Boolean> = _isUpdatingFilter.asStateFlow()

    private val _updateProgress = MutableStateFlow<Int?>(null)
    val updateProgress: StateFlow<Int?> = _updateProgress.asStateFlow()

    private val _isAddingCustomFilter = MutableStateFlow(false)
    val isAddingCustomFilter: StateFlow<Boolean> = _isAddingCustomFilter.asStateFlow()

    private val _addFilterProgress = MutableStateFlow<Int?>(null)
    val addFilterProgress: StateFlow<Int?> = _addFilterProgress.asStateFlow()

    private val _filterAddedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val filterAddedEvent: SharedFlow<Unit> = _filterAddedEvent.asSharedFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _supportErrorInfo = MutableStateFlow<app.pwhs.blockads.ui.common.SupportErrorInfo?>(null)
    val supportErrorInfo: StateFlow<app.pwhs.blockads.ui.common.SupportErrorInfo?> = _supportErrorInfo.asStateFlow()

    fun dismissSupportError() {
        _supportErrorInfo.value = null
    }

    fun showSupportError(info: app.pwhs.blockads.ui.common.SupportErrorInfo) {
        _supportErrorInfo.value = info
    }

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

    fun toggleStarredFilter(filter: FilterList) {
        viewModelScope.launch {
            appPreferences?.filter?.toggleStarredFilter(filter.url)
        }
    }

    fun addFilterList(
        name: String,
        url: String,
        category: String = FilterList.CATEGORY_AD,
        buildLocally: Boolean = false
    ) {
        viewModelScope.launch {
            val rawUrls = url.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (rawUrls.isEmpty()) {
                _events.toast(R.string.filter_error_invalid_url)
                return@launch
            }
            val combinedUrl = rawUrls.joinToString("\n")

            // Check duplicate
            val existing = filterListDao.getByUrl(combinedUrl) ?: filterListDao.getByOriginalUrl(combinedUrl)
            if (existing != null) {
                _events.toast(R.string.filter_error_duplicate_url)
                return@launch
            }

            if (buildLocally) {
                // Enqueue WorkManager job — returns immediately, compiles in background
                customFilterManager.enqueueLocalCompile(combinedUrl, name.trim(), category)
                _events.toast(R.string.filter_compile_enqueued)
                _filterAddedEvent.tryEmit(Unit)
            } else {
                _isAddingCustomFilter.value = true
                _addFilterProgress.value = 10
                val result = customFilterManager.addCustomFilter(
                    urls = rawUrls,
                    displayName = name.trim(),
                    category = category,
                    onProgress = { p -> _addFilterProgress.value = p }
                )
                _isAddingCustomFilter.value = false
                _addFilterProgress.value = null

                result.fold(
                    onSuccess = { _ ->
                        _events.toast(R.string.settings_add, listOf(": $name"))
                        _filterAddedEvent.tryEmit(Unit)

                        // Reload all filters so the new custom filter is active
                        filterRepo.loadAllEnabledFilters()
                        ServiceController.requestRestart(application.applicationContext)
                    },
                    onFailure = { error ->
                        _events.toast(R.string.filter_update_failed)
                        val rawError = error.message.orEmpty()
                        _supportErrorInfo.value = app.pwhs.blockads.ui.common.SupportErrorInfo(
                            title = application.getString(R.string.support_error_filter_title),
                            message = application.getString(R.string.support_error_filter_desc),
                            details = "Filter: $name\nURLs: $combinedUrl\nError: $rawError",
                            errorType = "CUSTOM_FILTER_ADD"
                        )
                    }
                )
            }
        }
    }

    fun editFilterList(
        filter: FilterList,
        name: String,
        url: String,
        category: String = filter.category,
        buildLocally: Boolean
    ) {
        viewModelScope.launch {
            val nameTrimmed = name.trim()
            val urlTrimmed = url.trim()
            if (nameTrimmed.isBlank() || urlTrimmed.isBlank()) {
                _events.toast(R.string.filter_error_invalid_url)
                return@launch
            }

            val isCurrentlyLocal = filter.trieUrl.startsWith("local://")
            val rawExistingUrl = filter.originalUrl.ifEmpty { filter.url }
            val urlChanged = urlTrimmed != rawExistingUrl.trim()
            val modeChanged = isCurrentlyLocal != buildLocally

            if (buildLocally) {
                if (urlChanged || modeChanged) {
                    customFilterManager.enqueueRecompileLocally(
                        filter.copy(
                            name = nameTrimmed,
                            url = urlTrimmed,
                            originalUrl = urlTrimmed,
                            category = category
                        )
                    )
                    _events.toast(R.string.filter_compile_enqueued)
                } else {
                    filterListDao.update(
                        filter.copy(
                            name = nameTrimmed,
                            category = category,
                            lastUpdated = System.currentTimeMillis()
                        )
                    )
                    _events.toast(R.string.filter_updated, listOf(filter.ruleCount))
                }
                _filterAddedEvent.tryEmit(Unit)
            } else {
                _isAddingCustomFilter.value = true
                _addFilterProgress.value = 10
                val result = customFilterManager.editCustomFilter(
                    filter = filter,
                    newName = nameTrimmed,
                    newUrl = urlTrimmed,
                    newCategory = category,
                    onProgress = { p -> _addFilterProgress.value = p }
                )
                _isAddingCustomFilter.value = false
                _addFilterProgress.value = null

                result.fold(
                    onSuccess = { updated ->
                        _events.toast(R.string.filter_updated, listOf(updated.ruleCount))
                        _filterAddedEvent.tryEmit(Unit)
                        filterRepo.loadAllEnabledFilters()
                        ServiceController.requestRestart(application.applicationContext)
                    },
                    onFailure = { error ->
                        _events.toast(R.string.filter_update_failed)
                        val rawError = error.message.orEmpty()
                        _supportErrorInfo.value = app.pwhs.blockads.ui.common.SupportErrorInfo(
                            title = application.getString(R.string.support_error_filter_title),
                            message = application.getString(R.string.support_error_filter_desc),
                            details = "Filter: $nameTrimmed\nURLs: $urlTrimmed\nError: $rawError",
                            errorType = "CUSTOM_FILTER_EDIT"
                        )
                    }
                )
            }
        }
    }

    fun requestDeleteFilterList(filter: FilterList) {
        if (filter.isBuiltIn) return
        _pendingDeleteFilterIds.value = _pendingDeleteFilterIds.value + filter.id
        _undoDeleteEvent.tryEmit(filter)
    }

    fun cancelDeleteFilterList(filter: FilterList) {
        _pendingDeleteFilterIds.value = _pendingDeleteFilterIds.value - filter.id
    }

    fun commitDeleteFilterList(filter: FilterList) {
        if (!_pendingDeleteFilterIds.value.contains(filter.id)) return
        _pendingDeleteFilterIds.value = _pendingDeleteFilterIds.value - filter.id
        deleteFilterList(filter)
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
            _updateProgress.value = 0

            var totalCount = 0
            var hasLocalFilters = false

            val enabledBuiltIn = filterListDao.getEnabled().filter { it.isBuiltIn }
            val customFilters = filterListDao.getAllNonBuiltIn().filter { it.isEnabled }
            val totalSteps = (enabledBuiltIn.size + customFilters.size).coerceAtLeast(1)
            var completedSteps = 0

            // 1. Update remote built-in filters with per-filter progress
            val result = filterRepo.forceUpdateAllEnabledFilters(
                onProgress = { completed, _ ->
                    completedSteps = completed
                    _updateProgress.value = ((completedSteps * 100) / totalSteps).coerceIn(0, 100)
                }
            )
            result.onSuccess { count -> totalCount += count }
            completedSteps = enabledBuiltIn.size
            _updateProgress.value = ((completedSteps * 100) / totalSteps).coerceIn(0, 100)

            // 2. Update all enabled custom filters
            for (filter in customFilters) {
                val isLocal = filter.trieUrl.startsWith("local://") &&
                        filter.bloomUrl.startsWith("local://")

                if (isLocal) {
                    customFilterManager.enqueueRecompileLocally(filter)
                    hasLocalFilters = true
                } else {
                    val customResult = customFilterManager.updateCustomFilter(filter)
                    customResult.onSuccess { updatedFilter ->
                        totalCount += updatedFilter.ruleCount
                    }
                }
                completedSteps++
                _updateProgress.value = (completedSteps * 100) / totalSteps
            }

            // Always reload engine in case any custom filters updated their binaries
            filterRepo.loadAllEnabledFilters()

            _isUpdatingFilter.value = false
            _updateProgress.value = null

            if (hasLocalFilters) {
                _events.toast(R.string.filter_compile_enqueued)
            }
            if (result.isSuccess || totalCount > 0) {
                _events.toast(
                    R.string.filter_updated,
                    listOf(totalCount)
                )
            } else {
                _events.toast(R.string.filter_update_failed)
            }
        }
    }
}
