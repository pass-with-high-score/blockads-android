package app.pwhs.blockads.ui.filter.overlap

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.utils.FilterOverlapAnalyzer
import app.pwhs.blockads.utils.FilterRuleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.NumberFormat

class FilterOverlapViewModel(
    private val filterListDao: FilterListDao,
    private val application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FilterOverlapUiState())
    val uiState: StateFlow<FilterOverlapUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<FilterOverlapEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<FilterOverlapEffect> = _effects.asSharedFlow()

    private var analyzeJob: Job? = null
    private var searchDebounceJob: Job? = null

    init {
        onIntent(FilterOverlapIntent.Analyze)
    }

    fun onIntent(intent: FilterOverlapIntent) {
        when (intent) {
            is FilterOverlapIntent.Analyze -> runAnalysis()
            is FilterOverlapIntent.SelectCategory -> {
                if (_uiState.value.selectedCategory != intent.category) {
                    _uiState.update { it.copy(selectedCategory = intent.category) }
                    runAnalysis()
                }
            }
            is FilterOverlapIntent.UpdateSearchQuery -> {
                _uiState.update { it.copy(searchQuery = intent.query) }
                searchDebounceJob?.cancel()
                searchDebounceJob = viewModelScope.launch {
                    delay(250)
                    runAnalysis()
                }
            }
            is FilterOverlapIntent.SelectFilter -> {
                val nextId = if (_uiState.value.selectedFilterId == intent.filterId) null else intent.filterId
                _uiState.update { it.copy(selectedFilterId = nextId) }
                runAnalysis()
            }
            is FilterOverlapIntent.SelectTab -> {
                _uiState.update { it.copy(selectedTab = intent.tabIndex) }
            }
            is FilterOverlapIntent.DisableFilter -> disableFilter(intent.filterId, intent.filterName)
            is FilterOverlapIntent.ExportCombinedList -> exportCombined(intent.uri)
            is FilterOverlapIntent.ShareCombinedList -> shareCombined()
            is FilterOverlapIntent.NavigateBack -> {
                _effects.tryEmit(FilterOverlapEffect.NavigateBack)
            }
        }
    }

    private suspend fun getEnabledRuleSources(): List<FilterRuleSource> {
        val filterDir = File(application.filesDir, "remote_filters")
        return filterListDao.getEnabled().mapNotNull { filter ->
            val trieFile = File(filterDir, "${filter.id}.trie").takeIf { it.exists() && it.length() > 0L }
            val cssFile = File(filterDir, "${filter.id}.css").takeIf { it.exists() && it.length() > 0L }
            val scriptletsFile = File(filterDir, "${filter.id}.scriptlets").takeIf { it.exists() && it.length() > 0L }
            val ipsFile = File(filterDir, "${filter.id}.ips").takeIf { it.exists() && it.length() > 0L }
            val modulesFile = File(filterDir, "${filter.id}.modules.json").takeIf { it.exists() && it.length() > 0L }

            if (trieFile != null || cssFile != null || scriptletsFile != null || ipsFile != null || modulesFile != null) {
                FilterRuleSource(
                    id = filter.id,
                    name = filter.name,
                    trieFile = trieFile,
                    cssFile = cssFile,
                    scriptletsFile = scriptletsFile,
                    ipsFile = ipsFile,
                    modulesFile = modulesFile
                )
            } else {
                null
            }
        }
    }

    private fun runAnalysis() {
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val current = _uiState.value
                val report = withContext(Dispatchers.IO) {
                    val sources = getEnabledRuleSources()
                    FilterOverlapAnalyzer.analyze(
                        sources = sources,
                        category = current.selectedCategory,
                        searchQuery = current.searchQuery,
                        filterId = current.selectedFilterId
                    )
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        filterCount = report.analyzedFilters.size,
                        totalRawEntries = report.totalRawEntries,
                        totalUniqueDomains = report.totalUniqueDomains,
                        overlappingDomainCount = report.overlappingDomainCount,
                        redundantEntries = report.redundantEntries,
                        redundancyPercent = report.redundancyPercent,
                        perFilterStats = report.perFilterStats,
                        pairwiseOverlaps = report.pairwiseOverlaps,
                        duplicateEntries = report.duplicateEntries,
                        hasMoreDuplicates = report.hasMoreDuplicates
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to analyze filter overlap")
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Analysis failed")
                }
            }
        }
    }

    private fun disableFilter(filterId: Long, filterName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                filterListDao.setEnabled(filterId, false)
            }
            if (_uiState.value.selectedFilterId == filterId) {
                _uiState.update { it.copy(selectedFilterId = null) }
            }
            _effects.tryEmit(
                FilterOverlapEffect.ShowMessage(
                    application.getString(R.string.filter_overlap_filter_disabled, filterName)
                )
            )
            runAnalysis()
        }
    }

    private fun exportCombined(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            try {
                val count = withContext(Dispatchers.IO) {
                    val sources = getEnabledRuleSources()
                    application.contentResolver.openOutputStream(uri)?.use { out ->
                        FilterOverlapAnalyzer.exportCombinedRules(
                            sources = sources,
                            category = _uiState.value.selectedCategory,
                            outputStream = out
                        )
                    } ?: 0
                }
                val formatted = NumberFormat.getIntegerInstance().format(count)
                _effects.tryEmit(
                    FilterOverlapEffect.ShowMessage(
                        application.getString(R.string.filter_overlap_exported, formatted)
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to export combined filter list")
                _effects.tryEmit(
                    FilterOverlapEffect.ShowMessage(
                        application.getString(R.string.filter_overlap_export_failed)
                    )
                )
            } finally {
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    private fun shareCombined() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            try {
                val file = withContext(Dispatchers.IO) {
                    val exportDir = File(application.cacheDir, "exports").apply { if (!exists()) mkdirs() }
                    val exportFile = File(exportDir, "blockads-combined-filters.txt")
                    val sources = getEnabledRuleSources()
                    exportFile.outputStream().use { out ->
                        FilterOverlapAnalyzer.exportCombinedRules(
                            sources = sources,
                            category = _uiState.value.selectedCategory,
                            outputStream = out
                        )
                    }
                    exportFile
                }
                val authority = "${application.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(application, authority, file)
                _effects.tryEmit(FilterOverlapEffect.ShareFile(uri, "text/plain"))
            } catch (e: Exception) {
                Timber.e(e, "Failed to share combined filter list")
                _effects.tryEmit(
                    FilterOverlapEffect.ShowMessage(
                        application.getString(R.string.filter_overlap_export_failed)
                    )
                )
            } finally {
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }
}
