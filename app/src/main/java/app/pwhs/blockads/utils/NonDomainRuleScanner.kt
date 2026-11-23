package app.pwhs.blockads.utils

import app.pwhs.blockads.data.models.FilterRuleKind
import app.pwhs.blockads.data.models.ModuleRulesPackage
import kotlinx.serialization.json.Json

internal class NonDomainScanResult(
    val perFilterTotal: IntArray,
    val perFilterOverlap: IntArray,
    val pairwiseCounts: Array<IntArray>,
    val totalRawEntries: Int,
    val totalUniqueRules: Int,
    val overlappingRuleCount: Int,
    val duplicateEntries: List<OverlappingRuleEntry>,
    val hasMoreDuplicates: Boolean,
    val ruleKindCounts: Map<FilterRuleKind, Int>
)

internal object NonDomainRuleScanner {

    private val json = Json { ignoreUnknownKeys = true }

    private data class RuleKey(
        val text: String,
        val kind: FilterRuleKind
    )

    fun scan(
        sources: List<FilterRuleSource>,
        category: OverlapRuleCategory,
        searchQuery: String = "",
        filterId: Long? = null,
        maxDisplayEntries: Int = FilterOverlapAnalyzer.DEFAULT_MAX_DISPLAY_ENTRIES
    ): NonDomainScanResult {
        val k = sources.size
        if (k == 0) {
            return NonDomainScanResult(
                perFilterTotal = IntArray(0),
                perFilterOverlap = IntArray(0),
                pairwiseCounts = Array(0) { IntArray(0) },
                totalRawEntries = 0,
                totalUniqueRules = 0,
                overlappingRuleCount = 0,
                duplicateEntries = emptyList(),
                hasMoreDuplicates = false,
                ruleKindCounts = emptyMap()
            )
        }

        val ruleMap = LinkedHashMap<RuleKey, Long>()
        val perFilterTotal = IntArray(k)
        val ruleKindCounts = mutableMapOf<FilterRuleKind, Int>()

        fun record(raw: String, kind: FilterRuleKind, filterIndex: Int) {
            if (!category.matches(kind)) return
            val clean = raw.trim()
            if (clean.isEmpty()) return

            val key = RuleKey(clean, kind)
            val currentMask = ruleMap[key] ?: 0L
            val filterBit = 1L shl (filterIndex and 63)
            if ((currentMask and filterBit) == 0L) {
                perFilterTotal[filterIndex]++
                ruleMap[key] = currentMask or filterBit
            }
        }

        for (i in sources.indices) {
            val src = sources[i]

            // 1. CSS rules (.css)
            src.cssFile?.takeIf { it.exists() && it.length() > 0L }?.let { cssFile ->
                runCatching {
                    cssFile.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("!")) return@forEachLine
                        val kind = when {
                            line.contains("#@#") -> FilterRuleKind.COSMETIC_EXCEPTIONS
                            line.contains("#$#") || line.contains(":style(", ignoreCase = true) -> FilterRuleKind.INJECT_CSS
                            line.contains("##") || line.contains("#?#") -> FilterRuleKind.COSMETIC
                            else -> null
                        }
                        if (kind != null) {
                            record(line, kind, i)
                        }
                    }
                }
            }

            // 2. Scriptlets (.scriptlets)
            src.scriptletsFile?.takeIf { it.exists() && it.length() > 0L }?.let { scriptFile ->
                runCatching {
                    scriptFile.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("!")) return@forEachLine
                        val kind = when {
                            line.contains("#@#+js(") || line.contains("#@%#//scriptlet(") -> FilterRuleKind.SCRIPTLET_EXCEPTIONS
                            line.contains("##+js(") || line.contains("#%#//scriptlet(") -> FilterRuleKind.SCRIPTLETS
                            else -> null
                        }
                        if (kind != null) {
                            record(line, kind, i)
                        }
                    }
                }
            }

            // 3. IPs (.ips)
            src.ipsFile?.takeIf { it.exists() && it.length() > 0L }?.let { ipsFile ->
                runCatching {
                    ipsFile.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachLine
                        record(line, FilterRuleKind.IPS, i)
                    }
                }
            }

            // 4. Modules (.modules.json)
            src.modulesFile?.takeIf { it.exists() && it.length() > 0L }?.let { modFile ->
                runCatching {
                    val pkg = json.decodeFromString<ModuleRulesPackage>(modFile.readText())
                    for (path in pkg.globalBlockPaths) {
                        record(path, FilterRuleKind.BLOCK_PATHS, i)
                    }
                    for (param in pkg.globalRemoveParams) {
                        record("\$removeparam=$param", FilterRuleKind.REMOVE_PARAMS, i)
                    }
                    val hasCssFile = src.cssFile?.exists() == true && src.cssFile.length() > 0L
                    if (!hasCssFile) {
                        for (ce in pkg.globalCssExceptions) {
                            record("#@#$ce", FilterRuleKind.COSMETIC_EXCEPTIONS, i)
                        }
                        for (ic in pkg.globalInjectCss) {
                            record(ic, FilterRuleKind.INJECT_CSS, i)
                        }
                    }
                    for ((domain, rules) in pkg.domains) {
                        for (bp in rules.blockPaths) {
                            record("$domain$bp", FilterRuleKind.BLOCK_PATHS, i)
                        }
                        for (rp in rules.removeParams) {
                            record("$domain\$removeparam=$rp", FilterRuleKind.REMOVE_PARAMS, i)
                        }
                        if (!hasCssFile) {
                            for (c in rules.css) {
                                record("$domain##$c", FilterRuleKind.COSMETIC, i)
                            }
                            for (ce in rules.cssExceptions) {
                                record("$domain#@#$ce", FilterRuleKind.COSMETIC_EXCEPTIONS, i)
                            }
                            for (ic in rules.injectCss) {
                                record("$domain#$#$ic", FilterRuleKind.INJECT_CSS, i)
                            }
                        }
                    }
                }
            }
        }

        val perFilterOverlap = IntArray(k)
        val pairwiseCounts = Array(k) { IntArray(k) }
        var totalRawEntries = 0
        var totalUniqueRules = 0
        var overlappingRuleCount = 0

        val duplicates = ArrayList<OverlappingRuleEntry>(maxDisplayEntries.coerceAtMost(1000))
        var hasMoreDuplicates = false
        val queryLower = searchQuery.trim().lowercase()

        for ((key, mask) in ruleMap) {
            val count = java.lang.Long.bitCount(mask)
            totalRawEntries += count
            totalUniqueRules++
            ruleKindCounts[key.kind] = (ruleKindCounts[key.kind] ?: 0) + 1

            if (count >= 2) {
                overlappingRuleCount++
                val indices = IntArray(count)
                var idx = 0
                for (i in 0 until k) {
                    if ((mask and (1L shl (i and 63))) != 0L) {
                        indices[idx++] = i
                        perFilterOverlap[i]++
                    }
                }

                for (aIdx in 0 until count) {
                    val a = indices[aIdx]
                    for (bIdx in aIdx + 1 until count) {
                        val b = indices[bIdx]
                        pairwiseCounts[a][b]++
                        pairwiseCounts[b][a]++
                    }
                }

                val matchesSelectedFilter = filterId == null || indices.any { sources[it].id == filterId }
                if (matchesSelectedFilter) {
                    if (queryLower.isEmpty() || key.text.lowercase().contains(queryLower)) {
                        if (duplicates.size < maxDisplayEntries) {
                            val names = indices.map { sources[it].name }
                            val ids = indices.map { sources[it].id }
                            duplicates.add(
                                OverlappingRuleEntry(
                                    rule = key.text,
                                    kind = key.kind,
                                    filterNames = names,
                                    filterIds = ids
                                )
                            )
                        } else {
                            hasMoreDuplicates = true
                        }
                    }
                }
            }
        }

        return NonDomainScanResult(
            perFilterTotal = perFilterTotal,
            perFilterOverlap = perFilterOverlap,
            pairwiseCounts = pairwiseCounts,
            totalRawEntries = totalRawEntries,
            totalUniqueRules = totalUniqueRules,
            overlappingRuleCount = overlappingRuleCount,
            duplicateEntries = duplicates,
            hasMoreDuplicates = hasMoreDuplicates,
            ruleKindCounts = ruleKindCounts
        )
    }

    fun collectUniqueRules(
        sources: List<FilterRuleSource>,
        category: OverlapRuleCategory
    ): List<Pair<String, FilterRuleKind>> {
        val result = LinkedHashMap<String, FilterRuleKind>()
        fun record(raw: String, kind: FilterRuleKind) {
            if (!category.matches(kind)) return
            val clean = raw.trim()
            if (clean.isNotEmpty()) {
                result.putIfAbsent(clean, kind)
            }
        }

        for (src in sources) {
            src.cssFile?.takeIf { it.exists() && it.length() > 0L }?.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("!")) return@forEachLine
                val kind = when {
                    line.contains("#@#") -> FilterRuleKind.COSMETIC_EXCEPTIONS
                    line.contains("#$#") || line.contains(":style(", ignoreCase = true) -> FilterRuleKind.INJECT_CSS
                    line.contains("##") || line.contains("#?#") -> FilterRuleKind.COSMETIC
                    else -> null
                }
                if (kind != null) record(line, kind)
            }

            src.scriptletsFile?.takeIf { it.exists() && it.length() > 0L }?.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("!")) return@forEachLine
                val kind = when {
                    line.contains("#@#+js(") || line.contains("#@%#//scriptlet(") -> FilterRuleKind.SCRIPTLET_EXCEPTIONS
                    line.contains("##+js(") || line.contains("#%#//scriptlet(") -> FilterRuleKind.SCRIPTLETS
                    else -> null
                }
                if (kind != null) record(line, kind)
            }

            src.ipsFile?.takeIf { it.exists() && it.length() > 0L }?.forEachLine { raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("!")) {
                    record(line, FilterRuleKind.IPS)
                }
            }

            src.modulesFile?.takeIf { it.exists() && it.length() > 0L }?.let { modFile ->
                runCatching {
                    val pkg = json.decodeFromString<ModuleRulesPackage>(modFile.readText())
                    for (p in pkg.globalBlockPaths) record(p, FilterRuleKind.BLOCK_PATHS)
                    for (rp in pkg.globalRemoveParams) record("\$removeparam=$rp", FilterRuleKind.REMOVE_PARAMS)
                    for ((domain, rules) in pkg.domains) {
                        for (bp in rules.blockPaths) record("$domain$bp", FilterRuleKind.BLOCK_PATHS)
                        for (rp in rules.removeParams) record("$domain\$removeparam=$rp", FilterRuleKind.REMOVE_PARAMS)
                    }
                }
            }
        }
        return result.map { it.key to it.value }
    }
}
