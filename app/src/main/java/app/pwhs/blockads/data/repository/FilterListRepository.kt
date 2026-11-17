package app.pwhs.blockads.data.repository

import android.content.Context
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.remote.FilterDownloadManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FilterListRepository(
    private val context: Context,
    private val filterListDao: FilterListDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val client: HttpClient,
    private val downloadManager: FilterDownloadManager,
    private val seeder: FilterListSeeder = FilterListSeeder(context, filterListDao, client)
) {

    companion object {
        private const val CACHE_DIR = "filter_cache"
        private const val FILTER_CACHE_SCHEMA_MARKER = ".filter_cache_v713"
        const val BLOCK_REASON_CUSTOM_RULE = "CUSTOM_RULE"
        const val BLOCK_REASON_FILTER_LIST = "FILTER_LIST"
        const val BLOCK_REASON_SECURITY = "SECURITY"
        const val BLOCK_REASON_FIREWALL = "FIREWALL"
        const val BLOCK_REASON_UPSTREAM_DNS = "upstream_dns"
    }

    // Paths to pre-compiled binary files for Go Native Engine (CSV strings)
    @Volatile
    private var adTriePaths: String = ""

    @Volatile
    private var securityTriePaths: String = ""

    @Volatile
    private var adBloomPaths: String = ""

    @Volatile
    private var securityBloomPaths: String = ""

    private val loadMutex = Mutex()

    init {
        File(context.filesDir, "remote_filters").mkdirs()
    }

    private val whitelistedDomains = ConcurrentHashMap.newKeySet<String>()
    private val customBlockDomains = ConcurrentHashMap.newKeySet<String>()
    private val customAllowDomains = ConcurrentHashMap.newKeySet<String>()
    private val appBlockRules = ConcurrentHashMap<String, MutableSet<String>>()
    private val appAllowRules = ConcurrentHashMap<String, MutableSet<String>>()

    private val _domainCountFlow = MutableStateFlow(0)
    val domainCountFlow: StateFlow<Int> = _domainCountFlow.asStateFlow()
    val domainCount: Int get() = _domainCountFlow.value

    fun getAdTriePath(): String = adTriePaths
    fun getSecurityTriePath(): String = securityTriePaths
    fun getAdBloomPath(): String = adBloomPaths
    fun getSecurityBloomPath(): String = securityBloomPaths

    fun getScriptletsPath(): String? {
        val file = File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun getCosmeticCssPath(): String? {
        val file = File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun getFilterModulesPath(): String? {
        val file = File(context.filesDir, "$CACHE_DIR/filter_modules.json")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /** Returns adPathPatterns from browser_rules.json as newline-separated string. */
    fun getAdPathPatterns(): String {
        return try {
            val json = context.assets.open("browser_rules.json").bufferedReader().use { it.readText() }
            // Simple extraction: find "adPathPatterns":[...] and parse the strings
            val match = """"adPathPatterns"\s*:\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(json)?.groupValues?.get(1) ?: return ""
            """"(.*?)"""".toRegex().findAll(match)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } catch (e: Exception) {
            Timber.w(e, "Failed to read ad path patterns from browser_rules.json")
            ""
        }
    }

    private inline fun checkDomainAndParents(
        domain: String,
        checker: (String) -> Boolean
    ): Boolean {
        if (checker(domain)) return true
        var d = domain
        while (d.contains('.')) {
            d = d.substringAfter('.')
            if (checker(d)) return true
            if (checker("*.$d")) return true
        }
        return false
    }

    fun isBlocked(domain: String): Boolean {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) return false
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) return true
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) return false
        return false
    }

    fun hasCustomRule(domain: String): Long {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) return 0L
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) return 0L
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) return 1L
        return -1L
    }

    fun checkAppRule(domain: String, packageName: String): Long {
        if (packageName.isEmpty()) return -1L
        val allowSet = appAllowRules[packageName]
        if (allowSet != null && checkDomainAndParents(domain) { allowSet.contains(it) }) return 0L
        val blockSet = appBlockRules[packageName]
        if (blockSet != null && checkDomainAndParents(domain) { blockSet.contains(it) }) return 1L
        return -1L
    }

    fun getBlockReason(domain: String): String {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) return ""
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) return BLOCK_REASON_CUSTOM_RULE
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) return ""
        return ""
    }

    suspend fun loadCustomRules() {
        val blockDomains = customDnsRuleDao.getBlockDomains()
        val allowDomains = customDnsRuleDao.getAllowDomains()
        val perAppRules = customDnsRuleDao.getPerAppRules()
        customBlockDomains.clear()
        customBlockDomains.addAll(blockDomains.map { it.lowercase() })
        customAllowDomains.clear()
        customAllowDomains.addAll(allowDomains.map { it.lowercase() })

        appBlockRules.clear()
        appAllowRules.clear()
        for (rule in perAppRules) {
            val pkg = rule.packageName.trim()
            val domain = rule.domain.lowercase().trim()
            if (pkg.isEmpty() || domain.isEmpty()) continue
            val targetMap = when (rule.ruleType) {
                app.pwhs.blockads.data.entities.RuleType.BLOCK -> appBlockRules
                app.pwhs.blockads.data.entities.RuleType.ALLOW -> appAllowRules
                else -> continue
            }
            targetMap.getOrPut(pkg) { ConcurrentHashMap.newKeySet() }.add(domain)
        }
        Timber.d(
            "Loaded ${customBlockDomains.size} block + ${customAllowDomains.size} allow custom rules, " +
                "${appBlockRules.size} app block + ${appAllowRules.size} app allow rule sets"
        )
    }

    suspend fun loadWhitelist() {
        val domains = whitelistDomainDao.getEnabledDomains()
        whitelistedDomains.clear()
        whitelistedDomains.addAll(domains.map { it.lowercase() })
        Timber.d("Loaded ${whitelistedDomains.size} whitelisted domains")
    }

    suspend fun seedDefaultsIfNeeded() = seeder.seedDefaultsIfNeeded()

    suspend fun fetchAndSyncRemoteFilterLists() = seeder.fetchAndSyncRemoteFilterLists()

    internal fun parseRemoteFilterJson(json: String): List<app.pwhs.blockads.data.remote.models.FilterList> =
        seeder.parseFilterJson(json)

    private suspend fun invalidateLegacyFilterCacheIfNeeded() {
        val marker = File(context.filesDir, FILTER_CACHE_SCHEMA_MARKER)
        if (marker.exists()) return
        try {
            File(context.filesDir, CACHE_DIR).deleteRecursively()
            val remoteDir = File(context.filesDir, "remote_filters").apply { mkdirs() }
            val allFilters = filterListDao.getAllSync()
            val localFilters = allFilters.filter { it.trieUrl.startsWith("local://") }
            val localIds = localFilters.map { it.id }.toSet()

            remoteDir.listFiles()?.forEach { file ->
                val idPrefix = file.name.substringBefore('.').toLongOrNull()
                if (file.name.endsWith(".modules.json") || idPrefix == null || idPrefix !in localIds) {
                    file.delete()
                }
            }

            val compiler = CustomFilterLocalCompiler(context, client, filterListDao)
            for (filter in localFilters) {
                val rawUrl = filter.originalUrl.ifEmpty { filter.url }.trim()
                if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    runCatching { compiler.recompileOrUpdateLocally(filter) }
                }
            }

            marker.writeText("713")
            Timber.d("Invalidated legacy filter cache and wrote $FILTER_CACHE_SCHEMA_MARKER")
        } catch (t: Throwable) {
            Timber.w(t, "Failed to invalidate legacy filter cache")
        }
    }

    suspend fun loadAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            try {
                invalidateLegacyFilterCacheIfNeeded()
                val enabledLists = filterListDao.getEnabled()
                if (enabledLists.isEmpty()) {
                    adTriePaths = ""
                    securityTriePaths = ""
                    adBloomPaths = ""
                    securityBloomPaths = ""
                    File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css").delete()
                    File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt").delete()
                    File(context.filesDir, "$CACHE_DIR/filter_modules.json").delete()
                    _domainCountFlow.value = 0
                    return@withContext Result.success(0)
                }

                val startTime = System.currentTimeMillis()
                val adTrieSb = StringBuilder()
                val secTrieSb = StringBuilder()
                val adBloomSb = StringBuilder()
                val secBloomSb = StringBuilder()
                var totalCount = 0

                for (filter in enabledLists) {
                    if (filter.bloomUrl.isEmpty() || filter.trieUrl.isEmpty()) {
                        Timber.d("Skipping ${filter.name}: no pre-compiled URLs")
                        continue
                    }
                    val result = downloadManager.downloadFilterList(filter, forceUpdate = false)
                    if (result.isSuccess) {
                        val paths = result.getOrNull() ?: continue
                        if (filter.category == FilterList.CATEGORY_SECURITY) {
                            paths.triePath?.let { secTrieSb.append(it).append(",") }
                            paths.bloomPath?.let { secBloomSb.append(it).append(",") }
                        } else {
                            paths.triePath?.let { adTrieSb.append(it).append(",") }
                            paths.bloomPath?.let { adBloomSb.append(it).append(",") }
                        }
                        // Update per-filter domainCount so UI shows the correct value
                        if (filter.domainCount != filter.ruleCount) {
                            filterListDao.updateStats(
                                id = filter.id,
                                count = filter.ruleCount,
                                timestamp = System.currentTimeMillis()
                            )
                        }
                        totalCount += filter.ruleCount
                    } else {
                        Timber.e("Failed to download filter: ${filter.name}")
                    }
                }

                adTriePaths = adTrieSb.toString().trimEnd(',')
                securityTriePaths = secTrieSb.toString().trimEnd(',')
                adBloomPaths = adBloomSb.toString().trimEnd(',')
                securityBloomPaths = secBloomSb.toString().trimEnd(',')

                compileCosmeticRules(enabledLists)
                compileScriptletRules(enabledLists)
                compileModuleRules(enabledLists)

                _domainCountFlow.value = totalCount
                val elapsed = System.currentTimeMillis() - startTime
                Timber.d("Filter paths loaded in ${elapsed}ms, totalRules=$totalCount")
                Result.success(totalCount)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load filters")
                Result.failure(e)
            }
        }
    }

    private suspend fun compileScriptletRules(enabledLists: List<FilterList>) =
        withContext(Dispatchers.IO) {
            try {
                val validLists = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
                if (validLists.isEmpty()) {
                    File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt").delete()
                    return@withContext
                }

                val sb = StringBuilder()
                var added = 0
                for (filter in validLists) {
                    val f = File(context.filesDir, "remote_filters/${filter.id}.scriptlets")
                    if (f.exists() && f.length() > 0) {
                        sb.append(f.readText())
                        if (!sb.endsWith("\n")) sb.append("\n")
                        added++
                    }
                }

                val outFile = File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt")
                if (added > 0) {
                    outFile.parentFile?.mkdirs()
                    outFile.writeText(sb.toString())
                    Timber.d("Wrote scriptlets ($added lists, ${sb.length} bytes)")
                } else {
                    outFile.delete()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to compile scriptlet rules")
            }
        }

    private suspend fun compileCosmeticRules(enabledLists: List<FilterList>) =
        withContext(Dispatchers.IO) {
            try {
                val validLists = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
                if (validLists.isEmpty()) {
                    File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css").delete()
                    return@withContext
                }

                val cssBuilder = StringBuilder()
                var rulesAdded = 0

                for (filter in validLists) {
                    val cssFile = File(context.filesDir, "remote_filters/${filter.id}.css")
                    if (cssFile.exists() && cssFile.length() > 0) {
                        val cssSnippet = downloadManager.getInjectableCss(cssFile)
                        if (cssSnippet.isNotEmpty()) {
                            cssBuilder.append(cssSnippet)
                            rulesAdded++
                        }
                    }
                }

                if (rulesAdded > 0) {
                    val finalCssFile = File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css")
                    finalCssFile.parentFile?.mkdirs()
                    finalCssFile.writeText(cssBuilder.toString())
                    Timber.d("Wrote cosmetic CSS (${cssBuilder.length} bytes)")
                } else {
                    File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css").delete()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to compile cosmetic rules")
            }
        }

    private suspend fun compileModuleRules(enabledLists: List<FilterList>) =
        withContext(Dispatchers.IO) {
            try {
                val outFile = File(context.filesDir, "$CACHE_DIR/filter_modules.json")
                val validLists = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
                if (validLists.isEmpty()) {
                    outFile.delete()
                    return@withContext
                }

                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                val mergedDomains = mutableMapOf<String, Pair<MutableSet<String>, MutableSet<String>>>()
                val mergedGlobalPaths = mutableSetOf<String>()

                for (filter in validLists) {
                    val modFile = File(context.filesDir, "remote_filters/${filter.id}.modules.json")
                    if (!modFile.exists() || modFile.length() == 0L) continue
                    val pkg = runCatching {
                        json.decodeFromString<app.pwhs.blockads.data.models.ModuleRulesPackage>(modFile.readText())
                    }.getOrNull() ?: continue

                    mergedGlobalPaths.addAll(pkg.globalBlockPaths)
                    for ((domain, rules) in pkg.domains) {
                        val pair = mergedDomains.getOrPut(domain.lowercase()) { mutableSetOf<String>() to mutableSetOf<String>() }
                        pair.first.addAll(rules.css)
                        pair.second.addAll(rules.blockPaths)
                    }
                }

                if (mergedDomains.isNotEmpty() || mergedGlobalPaths.isNotEmpty()) {
                    val outPkg = app.pwhs.blockads.data.models.ModuleRulesPackage(
                        name = "merged_filters",
                        ruleCount = mergedDomains.size + mergedGlobalPaths.size,
                        domains = mergedDomains.mapValues { (_, pair) ->
                            app.pwhs.blockads.data.models.DomainModuleRules(
                                css = pair.first.toList(),
                                blockPaths = pair.second.toList()
                            )
                        },
                        globalBlockPaths = mergedGlobalPaths.toList()
                    )
                    outFile.parentFile?.mkdirs()
                    outFile.writeText(
                        json.encodeToString(app.pwhs.blockads.data.models.ModuleRulesPackage.serializer(), outPkg)
                    )
                    Timber.d("Wrote filter modules (${mergedDomains.size} domains, ${mergedGlobalPaths.size} global paths)")
                } else {
                    outFile.delete()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to compile filter module rules")
            }
        }

    suspend fun forceUpdateAllEnabledFilters(
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Sync the latest metadata for all built-in filters to the DB
            fetchAndSyncRemoteFilterLists()

            // Fetch the enabled built-in filters (now with updated ruleCounts and URLs)
            val enabledBuiltIn = filterListDao.getEnabled().filter { it.isBuiltIn }
            val total = enabledBuiltIn.size
            var completed = 0

            var totalCount = 0

            for (filter in enabledBuiltIn) {
                // Force download the binary files from the remote server
                val result = downloadManager.downloadFilterList(filter, forceUpdate = true)
                if (result.isSuccess) {
                    filterListDao.updateStats(
                        id = filter.id,
                        count = filter.ruleCount,
                        timestamp = System.currentTimeMillis()
                    )
                    totalCount += filter.ruleCount
                } else {
                    Timber.e("Failed to force update built-in filter: ${filter.name}")
                }
                completed++
                onProgress?.invoke(completed, total)
            }

            // Reload into the Go engine
            loadAllEnabledFilters()
            Result.success(totalCount)
        } catch (e: Exception) {
            Timber.e(e, "Failed to force update all enabled filters")
            Result.failure(e)
        }
    }

    suspend fun updateSingleFilter(filter: FilterList): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (filter.isBuiltIn) {
                // Fetch the latest remote configs
                fetchAndSyncRemoteFilterLists()

                // Get the updated entity from DB to have the latest ruleCount & URLs
                val updatedFilter = filterListDao.getById(filter.id) ?: filter

                val result = downloadManager.downloadFilterList(updatedFilter, forceUpdate = true)
                if (result.isSuccess) {
                    filterListDao.updateStats(
                        id = updatedFilter.id,
                        count = updatedFilter.ruleCount,
                        timestamp = System.currentTimeMillis()
                    )
                    loadAllEnabledFilters()
                    Result.success(updatedFilter.ruleCount)
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
                }
            } else {
                val result = downloadManager.downloadFilterList(filter, forceUpdate = true)
                if (result.isSuccess) {
                    filterListDao.updateStats(
                        id = filter.id,
                        count = filter.ruleCount,
                        timestamp = System.currentTimeMillis()
                    )
                    loadAllEnabledFilters()
                    Result.success(filter.ruleCount)
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
                }
            }
        } catch (e: Exception) {
            Timber.d("Failed to update ${filter.name}: $e")
            Result.failure(e)
        }
    }

    suspend fun findBlockingFilterLists(targetDomain: String): List<String> =
        withContext(Dispatchers.IO) {
            val enabledLists = filterListDao.getEnabled()
            if (enabledLists.isEmpty()) return@withContext emptyList()

            val matchedListNames = mutableListOf<String>()
            for (filter in enabledLists) {
                val trieFile = File(context.filesDir, "remote_filters/${filter.id}.trie")
                if (!trieFile.exists() || trieFile.length() == 0L) continue
                try {
                    if (tunnel.Tunnel.checkDomainInTrieFile(trieFile.absolutePath, targetDomain)) {
                        matchedListNames.add(filter.name)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error scanning trie for ${filter.name}")
                }
            }
            return@withContext matchedListNames
        }

    suspend fun checkDomainInFilter(filterId: Long, domain: String): Boolean =
        withContext(Dispatchers.IO) {
            val trieFile = File(context.filesDir, "remote_filters/$filterId.trie")
            if (!trieFile.exists() || trieFile.length() == 0L) return@withContext false

            return@withContext try {
                tunnel.Tunnel.checkDomainInTrieFile(trieFile.absolutePath, domain)
            } catch (e: Exception) {
                Timber.e(e, "Error scanning trie for $filterId regarding $domain")
                false
            }
        }

}
