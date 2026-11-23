package app.pwhs.blockads.utils

import app.pwhs.blockads.data.models.FilterRuleKind
import java.io.File

data class FilterRuleSource(
    val id: Long,
    val name: String,
    val trieFile: File? = null,
    val cssFile: File? = null,
    val scriptletsFile: File? = null,
    val ipsFile: File? = null,
    val modulesFile: File? = null
)

typealias FilterTrieSource = FilterRuleSource

enum class OverlapRuleCategory {
    ALL,
    DOMAINS,
    COSMETIC,
    SCRIPTLETS,
    IPS,
    URL_RULES;

    fun matches(kind: FilterRuleKind): Boolean = when (this) {
        ALL -> true
        DOMAINS -> kind == FilterRuleKind.DOMAINS
        COSMETIC -> kind == FilterRuleKind.COSMETIC ||
            kind == FilterRuleKind.COSMETIC_EXCEPTIONS ||
            kind == FilterRuleKind.INJECT_CSS
        SCRIPTLETS -> kind == FilterRuleKind.SCRIPTLETS ||
            kind == FilterRuleKind.SCRIPTLET_EXCEPTIONS
        IPS -> kind == FilterRuleKind.IPS
        URL_RULES -> kind == FilterRuleKind.BLOCK_PATHS ||
            kind == FilterRuleKind.REMOVE_PARAMS
    }
}

data class FilterOverlapStat(
    val filterId: Long,
    val filterName: String,
    val totalDomains: Int,
    val overlappingDomains: Int,
    val uniqueOnlyDomains: Int
) {
    val totalRules: Int get() = totalDomains
    val overlappingRules: Int get() = overlappingDomains
    val uniqueOnlyRules: Int get() = uniqueOnlyDomains

    val overlapPercent: Float
        get() = if (totalDomains > 0) (overlappingDomains * 100f) / totalDomains else 0f
}

data class PairwiseFilterOverlap(
    val filterAId: Long,
    val filterAName: String,
    val filterBId: Long,
    val filterBName: String,
    val sharedDomains: Int
) {
    val sharedRules: Int get() = sharedDomains
}

data class OverlappingRuleEntry(
    val rule: String,
    val kind: FilterRuleKind = FilterRuleKind.DOMAINS,
    val filterNames: List<String>,
    val filterIds: List<Long>
) {
    val domain: String get() = rule
}

typealias OverlappingDomainEntry = OverlappingRuleEntry

data class FilterOverlapReport(
    val analyzedFilters: List<FilterRuleSource>,
    val totalRawEntries: Int,
    val totalUniqueDomains: Int,
    val overlappingDomainCount: Int,
    val perFilterStats: List<FilterOverlapStat>,
    val pairwiseOverlaps: List<PairwiseFilterOverlap>,
    val duplicateEntries: List<OverlappingRuleEntry>,
    val hasMoreDuplicates: Boolean,
    val ruleKindCounts: Map<FilterRuleKind, Int> = emptyMap()
) {
    val redundantEntries: Int
        get() = (totalRawEntries - totalUniqueDomains).coerceAtLeast(0)

    val redundancyPercent: Float
        get() = if (totalRawEntries > 0) (redundantEntries * 100f) / totalRawEntries else 0f

    val totalUniqueRules: Int get() = totalUniqueDomains
    val overlappingRuleCount: Int get() = overlappingDomainCount
}
