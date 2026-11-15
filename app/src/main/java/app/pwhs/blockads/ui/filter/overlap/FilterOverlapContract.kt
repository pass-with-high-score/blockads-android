package app.pwhs.blockads.ui.filter.overlap

import android.net.Uri
import app.pwhs.blockads.utils.FilterOverlapStat
import app.pwhs.blockads.utils.OverlappingDomainEntry
import app.pwhs.blockads.utils.PairwiseFilterOverlap

data class FilterOverlapUiState(
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val filterCount: Int = 0,
    val totalRawEntries: Int = 0,
    val totalUniqueDomains: Int = 0,
    val overlappingDomainCount: Int = 0,
    val redundantEntries: Int = 0,
    val redundancyPercent: Float = 0f,
    val perFilterStats: List<FilterOverlapStat> = emptyList(),
    val pairwiseOverlaps: List<PairwiseFilterOverlap> = emptyList(),
    val duplicateEntries: List<OverlappingDomainEntry> = emptyList(),
    val hasMoreDuplicates: Boolean = false,
    val searchQuery: String = "",
    val selectedFilterId: Long? = null,
    val selectedTab: Int = 0,
    val error: String? = null
)

sealed interface FilterOverlapIntent {
    data object Analyze : FilterOverlapIntent
    data class UpdateSearchQuery(val query: String) : FilterOverlapIntent
    data class SelectFilter(val filterId: Long?) : FilterOverlapIntent
    data class SelectTab(val tabIndex: Int) : FilterOverlapIntent
    data class DisableFilter(val filterId: Long, val filterName: String) : FilterOverlapIntent
    data class ExportCombinedList(val uri: Uri) : FilterOverlapIntent
    data object NavigateBack : FilterOverlapIntent
}

sealed interface FilterOverlapEffect {
    data class ShowMessage(val message: String) : FilterOverlapEffect
    data object NavigateBack : FilterOverlapEffect
}
