package app.pwhs.blockads.ui.filter.detail

import android.content.Context
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.models.ModuleRulesPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File

enum class FilterRuleKind {
    DOMAINS,
    IPS,
    COSMETIC,
    COSMETIC_EXCEPTIONS,
    INJECT_CSS,
    SCRIPTLETS,
    SCRIPTLET_EXCEPTIONS,
    BLOCK_PATHS,
    REMOVE_PARAMS
}

data class FilterRuleTypeItem(
    val kind: FilterRuleKind,
    val count: Int,
    val samples: List<String> = emptyList()
)

data class FilterRuleBreakdown(
    val items: List<FilterRuleTypeItem> = emptyList(),
    val totalDiskBytes: Long = 0L,
    val isDownloaded: Boolean = false,
    val hasAuthoritativeStats: Boolean = false
) {
    val totalRules: Int
        get() = items.sumOf { it.count }
}

internal object FilterRuleBreakdownLoader {
    private const val REMOTE_FILTERS_DIR = "remote_filters"
    private const val MAX_SAMPLES = 4
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun load(context: Context, filter: FilterList): FilterRuleBreakdown =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, REMOTE_FILTERS_DIR)
            val id = filter.id
            val trieFile = File(dir, "$id.trie")
            val bloomFile = File(dir, "$id.bloom")
            val ipsFile = File(dir, "$id.ips")
            val cssFile = File(dir, "$id.css")
            val scriptletsFile = File(dir, "$id.scriptlets")
            val modulesFile = File(dir, "$id.modules.json")
            val infoFile = File(dir, "$id.info.json")

            val allFiles = listOf(trieFile, bloomFile, ipsFile, cssFile, scriptletsFile, modulesFile, infoFile)
            val totalBytes = allFiles.sumOf { if (it.exists()) it.length() else 0L }
            val isDownloaded = trieFile.exists() || ipsFile.exists() || cssFile.exists() ||
                scriptletsFile.exists() || modulesFile.exists()

            // 1. Optional server-provided stats from info.json (preferred when present)
            val statsObj = runCatching {
                if (infoFile.exists() && infoFile.length() > 0L) {
                    JSONObject(infoFile.readText()).optJSONObject("stats")
                } else null
            }.getOrNull()

            // 2. Scan sidecars for exact counts (fallback) & real samples
            val ipsSamples = mutableListOf<String>()
            var ipsCount = 0
            if (ipsFile.exists() && ipsFile.length() > 0L) {
                runCatching {
                    ipsFile.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("!")) {
                            ipsCount++
                            if (ipsSamples.size < MAX_SAMPLES) ipsSamples.add(line)
                        }
                    }
                }
            }

            val cosmeticSamples = mutableListOf<String>()
            val cosmeticExSamples = mutableListOf<String>()
            val injectCssSamples = mutableListOf<String>()
            var cosmeticCount = 0
            var cosmeticExCount = 0
            var injectCssCount = 0
            if (cssFile.exists() && cssFile.length() > 0L) {
                runCatching {
                    cssFile.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("!")) return@forEachLine
                        when {
                            line.contains("#@#") -> {
                                cosmeticExCount++
                                if (cosmeticExSamples.size < MAX_SAMPLES) cosmeticExSamples.add(line)
                            }
                            line.contains("#$#") || line.contains(":style(", ignoreCase = true) -> {
                                injectCssCount++
                                if (injectCssSamples.size < MAX_SAMPLES) injectCssSamples.add(line)
                            }
                            line.contains("##") || line.contains("#?#") -> {
                                cosmeticCount++
                                if (cosmeticSamples.size < MAX_SAMPLES) cosmeticSamples.add(line)
                            }
                        }
                    }
                }
            }

            val scriptletSamples = mutableListOf<String>()
            val scriptletExSamples = mutableListOf<String>()
            var scriptletCount = 0
            var scriptletExCount = 0
            if (scriptletsFile.exists() && scriptletsFile.length() > 0L) {
                runCatching {
                    scriptletsFile.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("!")) return@forEachLine
                        if (line.contains("#@#+js(") || line.contains("#@%#//scriptlet(")) {
                            scriptletExCount++
                            if (scriptletExSamples.size < MAX_SAMPLES) scriptletExSamples.add(line)
                        } else if (line.contains("##+js(") || line.contains("#%#//scriptlet(")) {
                            scriptletCount++
                            if (scriptletSamples.size < MAX_SAMPLES) scriptletSamples.add(line)
                        }
                    }
                }
            }

            val pathSamples = mutableListOf<String>()
            val removeParamSamples = mutableListOf<String>()
            var blockPathsCount = 0
            var removeParamsCount = 0
            if (modulesFile.exists() && modulesFile.length() > 0L) {
                runCatching {
                    val pkg = json.decodeFromString<ModuleRulesPackage>(modulesFile.readText())
                    blockPathsCount += pkg.globalBlockPaths.size
                    pkg.globalBlockPaths.take(MAX_SAMPLES - pathSamples.size).forEach { pathSamples.add(it) }
                    removeParamsCount += pkg.globalRemoveParams.size
                    pkg.globalRemoveParams.take(MAX_SAMPLES - removeParamSamples.size).forEach {
                        removeParamSamples.add("\$removeparam=$it")
                    }
                    if (cosmeticExCount == 0 && pkg.globalCssExceptions.isNotEmpty()) {
                        cosmeticExCount += pkg.globalCssExceptions.size
                        pkg.globalCssExceptions.take(MAX_SAMPLES - cosmeticExSamples.size).forEach {
                            cosmeticExSamples.add("#@#$it")
                        }
                    }
                    if (injectCssCount == 0 && pkg.globalInjectCss.isNotEmpty()) {
                        injectCssCount += pkg.globalInjectCss.size
                        pkg.globalInjectCss.take(MAX_SAMPLES - injectCssSamples.size).forEach {
                            injectCssSamples.add(it)
                        }
                    }

                    for ((domain, rules) in pkg.domains) {
                        blockPathsCount += rules.blockPaths.size
                        for (p in rules.blockPaths) {
                            if (pathSamples.size >= MAX_SAMPLES) break
                            pathSamples.add("$domain$p")
                        }
                        removeParamsCount += rules.removeParams.size
                        for (rp in rules.removeParams) {
                            if (removeParamSamples.size >= MAX_SAMPLES) break
                            removeParamSamples.add("\$removeparam=$rp ($domain)")
                        }
                        if (!cssFile.exists()) {
                            cosmeticCount += rules.css.size
                            for (c in rules.css) {
                                if (cosmeticSamples.size >= MAX_SAMPLES) break
                                cosmeticSamples.add("$domain##$c")
                            }
                            cosmeticExCount += rules.cssExceptions.size
                            for (ce in rules.cssExceptions) {
                                if (cosmeticExSamples.size >= MAX_SAMPLES) break
                                cosmeticExSamples.add("$domain#@#$ce")
                            }
                            injectCssCount += rules.injectCss.size
                            for (ic in rules.injectCss) {
                                if (injectCssSamples.size >= MAX_SAMPLES) break
                                injectCssSamples.add("$domain#$#$ic")
                            }
                        }
                    }
                }
            }

            // Merge server stats (when present) with local sidecar scans
            val finalIps = statsObj?.optInt("ips", -1)?.takeIf { it >= 0 } ?: ipsCount
            val finalCosmetic = statsObj?.optInt("cosmetic", -1)?.takeIf { it >= 0 } ?: cosmeticCount
            val finalCosmeticEx = statsObj?.optInt("cosmeticExceptions", -1)?.takeIf { it >= 0 } ?: cosmeticExCount
            val finalInjectCss = statsObj?.optInt("injectCss", -1)?.takeIf { it >= 0 } ?: injectCssCount
            val finalScriptlets = statsObj?.optInt("scriptlets", -1)?.takeIf { it >= 0 } ?: scriptletCount
            val finalScriptletsEx = statsObj?.optInt("scriptletExceptions", -1)?.takeIf { it >= 0 } ?: scriptletExCount
            val finalBlockPaths = statsObj?.optInt("blockPaths", -1)?.takeIf { it >= 0 } ?: blockPathsCount
            val finalRemoveParams = statsObj?.optInt("removeParams", -1)?.takeIf { it >= 0 } ?: removeParamsCount

            val nonDomainTotal = finalIps + finalCosmetic + finalCosmeticEx + finalInjectCss +
                finalScriptlets + finalScriptletsEx + finalBlockPaths + finalRemoveParams

            val finalDomains = statsObj?.optInt("domains", -1)?.takeIf { it >= 0 } ?: run {
                // Empty trie header is <= 32 bytes; avoid showing IP-only lists as DNS domains.
                val hasNonEmptyTrie = trieFile.exists() && trieFile.length() > 32L
                // Use filter.ruleCount (immutable compiler output) rather than domainCount to avoid feedback loops.
                val baseCount = filter.ruleCount.takeIf { it > 0 } ?: filter.domainCount
                when {
                    hasNonEmptyTrie -> baseCount.coerceAtLeast(1)
                    !isDownloaded -> baseCount
                    else -> 0
                }
            }

            val items = buildList {
                if (finalDomains > 0) add(FilterRuleTypeItem(FilterRuleKind.DOMAINS, finalDomains))
                if (finalIps > 0) add(FilterRuleTypeItem(FilterRuleKind.IPS, finalIps, ipsSamples))
                if (finalCosmetic > 0) add(FilterRuleTypeItem(FilterRuleKind.COSMETIC, finalCosmetic, cosmeticSamples))
                if (finalCosmeticEx > 0) add(FilterRuleTypeItem(FilterRuleKind.COSMETIC_EXCEPTIONS, finalCosmeticEx, cosmeticExSamples))
                if (finalInjectCss > 0) add(FilterRuleTypeItem(FilterRuleKind.INJECT_CSS, finalInjectCss, injectCssSamples))
                if (finalScriptlets > 0) add(FilterRuleTypeItem(FilterRuleKind.SCRIPTLETS, finalScriptlets, scriptletSamples))
                if (finalScriptletsEx > 0) add(FilterRuleTypeItem(FilterRuleKind.SCRIPTLET_EXCEPTIONS, finalScriptletsEx, scriptletExSamples))
                if (finalBlockPaths > 0) add(FilterRuleTypeItem(FilterRuleKind.BLOCK_PATHS, finalBlockPaths, pathSamples))
                if (finalRemoveParams > 0) add(FilterRuleTypeItem(FilterRuleKind.REMOVE_PARAMS, finalRemoveParams, removeParamSamples))
            }

            FilterRuleBreakdown(
                items = items,
                totalDiskBytes = totalBytes,
                isDownloaded = isDownloaded,
                hasAuthoritativeStats = statsObj != null || (nonDomainTotal > 0 && filter.ruleCount > 0)
            )
        }
}
