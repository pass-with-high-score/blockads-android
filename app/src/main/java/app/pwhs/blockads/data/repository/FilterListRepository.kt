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
    private val downloadManager: FilterDownloadManager
) {

    companion object {
        private const val CACHE_DIR = "filter_cache"
        const val BLOCK_REASON_CUSTOM_RULE = "CUSTOM_RULE"
        const val BLOCK_REASON_FILTER_LIST = "FILTER_LIST"
        const val BLOCK_REASON_SECURITY = "SECURITY"
        const val BLOCK_REASON_FIREWALL = "FIREWALL"
        const val BLOCK_REASON_UPSTREAM_DNS = "upstream_dns"

        private const val FILTER_LIST_JSON_URL =
            "https://raw.githubusercontent.com/pass-with-high-score/blockads-default-filter/refs/heads/main/output/filter_lists.json"
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

    fun getBlockReason(domain: String): String {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) return ""
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) return BLOCK_REASON_CUSTOM_RULE
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) return ""
        return ""
    }

    suspend fun loadCustomRules() {
        val blockDomains = customDnsRuleDao.getBlockDomains()
        val allowDomains = customDnsRuleDao.getAllowDomains()
        customBlockDomains.clear()
        customBlockDomains.addAll(blockDomains.map { it.lowercase() })
        customAllowDomains.clear()
        customAllowDomains.addAll(allowDomains.map { it.lowercase() })
        Timber.d("Loaded ${customBlockDomains.size} block + ${customAllowDomains.size} allow custom rules")
    }

    suspend fun loadWhitelist() {
        val domains = whitelistDomainDao.getAllDomains()
        whitelistedDomains.clear()
        whitelistedDomains.addAll(domains.map { it.lowercase() })
        Timber.d("Loaded ${whitelistedDomains.size} whitelisted domains")
    }

    // ────────────────────────────────────────────────────────────────────
    // Seeding & Remote Sync
    // ────────────────────────────────────────────────────────────────────

    /**
     * Seeds default filter lists by fetching from the remote JSON URL.
     * Updates existing entries so bloomUrl/trieUrl/cssUrl/ruleCount stay current.
     */
    suspend fun seedDefaultsIfNeeded() {
        fetchAndSyncRemoteFilterLists()
    }

    /**
     * Fetches the remote filter_lists.json and syncs pre-compiled URLs to the local DB.
     * Keeps bloomUrl/trieUrl/cssUrl/ruleCount fresh when the server regenerates binaries.
     */
    suspend fun fetchAndSyncRemoteFilterLists() = withContext(Dispatchers.IO) {
        try {
            val channel = client.get(FILTER_LIST_JSON_URL).bodyAsChannel()
            val buffer = ByteArray(256 * 1024)
            val output = java.io.ByteArrayOutputStream()
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read > 0) output.write(buffer, 0, read)
            }
            val jsonString = output.toString(Charsets.UTF_8.name())
            val remoteLists = parseRemoteFilterJson(jsonString)
            if (remoteLists.isEmpty()) return@withContext

            val existingLists = filterListDao.getAllSync()
            val existingByName = existingLists.associateBy { it.name }

            for (remote in remoteLists) {
                val existing = existingByName[remote.name]
                val category = if (remote.category == "security") FilterList.CATEGORY_SECURITY else FilterList.CATEGORY_AD
                if (existing != null) {
                    val needsUpdate = existing.url != (remote.originalUrl ?: "") ||
                        existing.description != (remote.description ?: "") ||
                        existing.category != category ||
                        existing.bloomUrl != remote.bloomUrl ||
                        existing.trieUrl != remote.trieUrl ||
                        existing.cssUrl != (remote.cssUrl ?: "") ||
                        existing.scriptletsUrl != (remote.scriptletsUrl ?: "") ||
                        existing.ruleCount != remote.ruleCount ||
                        existing.domainCount != remote.ruleCount ||
                        existing.originalUrl != (remote.originalUrl ?: existing.originalUrl) ||
                        !existing.isBuiltIn

                    if (needsUpdate) {
                        filterListDao.update(
                            existing.copy(
                                url = remote.originalUrl ?: "",
                                description = remote.description ?: "",
                                category = category,
                                bloomUrl = remote.bloomUrl,
                                trieUrl = remote.trieUrl,
                                domainCount = remote.ruleCount,
                                cssUrl = remote.cssUrl ?: "",
                                scriptletsUrl = remote.scriptletsUrl ?: "",
                                ruleCount = remote.ruleCount,
                                originalUrl = remote.originalUrl ?: existing.originalUrl,
                                isBuiltIn = true
                            )
                        )
                        Timber.d("Updated remote filter: ${remote.name}")
                    }
                } else {
                    filterListDao.insert(
                        FilterList(
                            name = remote.name,
                            url = remote.originalUrl ?: "",
                            description = remote.description ?: "",
                            isEnabled = remote.isEnabled,
                            isBuiltIn = remote.isBuiltIn,
                            category = category,
                            ruleCount = remote.ruleCount,
                            bloomUrl = remote.bloomUrl,
                            trieUrl = remote.trieUrl,
                            cssUrl = remote.cssUrl ?: "",
                            scriptletsUrl = remote.scriptletsUrl ?: "",
                            originalUrl = remote.originalUrl ?: ""
                        )
                    )
                    Timber.d("Inserted new remote filter: ${remote.name}")
                }
            }

            val remoteNames = remoteLists.map { it.name }.toSet()
            val obsolete = existingLists.filter { it.isBuiltIn && it.name !in remoteNames }
            for (o in obsolete) {
                filterListDao.delete(o)
                Timber.d("Removed obsolete built-in filter: ${o.name}")
            }

            Timber.d("Synced ${remoteLists.size} filters from remote JSON")
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch remote filter list JSON")
        }
    }

    /**
     * Simple JSON parser for the filter_lists.json array.
     */
    private fun parseRemoteFilterJson(json: String): List<app.pwhs.blockads.data.remote.models.FilterList> {
        return try {
            val results = mutableListOf<app.pwhs.blockads.data.remote.models.FilterList>()
            val objects = json.split("},").map {
                it.trim().removePrefix("[").removeSuffix("]").trim() + "}"
            }

            for (obj in objects) {
                val cleaned = obj.trim().removePrefix("{").removeSuffix("}").removeSuffix("},")
                if (cleaned.isBlank()) continue

                fun extractString(key: String): String? {
                    val pattern = "\"$key\"\\s*:\\s*\"(.*?)\"".toRegex()
                    return pattern.find(cleaned)?.groupValues?.get(1)
                        ?.replace("\\u0026", "&")
                }

                fun extractInt(key: String): Int {
                    val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
                    return pattern.find(cleaned)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }

                fun extractBoolean(key: String): Boolean {
                    val pattern = "\"$key\"\\s*:\\s*(true|false)".toRegex()
                    return pattern.find(cleaned)?.groupValues?.get(1) == "true"
                }

                val name = extractString("name") ?: continue
                val bloomUrl = extractString("bloomUrl") ?: continue
                val trieUrl = extractString("trieUrl") ?: continue

                results.add(
                    app.pwhs.blockads.data.remote.models.FilterList(
                        name = name,
                        id = extractString("id") ?: name.lowercase().replace(" ", "_"),
                        description = extractString("description"),
                        isEnabled = extractBoolean("isEnabled"),
                        isBuiltIn = extractBoolean("isBuiltIn"),
                        category = extractString("category"),
                        ruleCount = extractInt("ruleCount"),
                        bloomUrl = bloomUrl,
                        trieUrl = trieUrl,
                        cssUrl = extractString("cssUrl"),
                        scriptletsUrl = extractString("scriptletsUrl"),
                        originalUrl = extractString("originalUrl")
                    )
                )
            }
            results
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse remote filter JSON")
            emptyList()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Filter Loading & Download
    // ────────────────────────────────────────────────────────────────────

    /**
     * Load all enabled filter lists using FilterDownloadManager.
     * Downloads pre-compiled .bloom/.trie files and collects paths as CSV strings for the Go engine.
     */
    suspend fun loadAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            try {
                val enabledLists = filterListDao.getEnabled()
                if (enabledLists.isEmpty()) {
                    adTriePaths = ""
                    securityTriePaths = ""
                    adBloomPaths = ""
                    securityBloomPaths = ""
                    File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css").delete()
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
                    if (filter.scriptletsUrl.isEmpty()) continue
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
                if (validLists.isEmpty()) return@withContext

                val cssBuilder = StringBuilder()
                var rulesAdded = 0

                for (filter in validLists) {
                    if (filter.cssUrl.isEmpty()) continue
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

    suspend fun forceUpdateAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Sync the latest metadata for all built-in filters to the DB
            fetchAndSyncRemoteFilterLists()

            // Fetch the enabled built-in filters (now with updated ruleCounts and URLs)
            val enabledBuiltIn = filterListDao.getEnabled().filter { it.isBuiltIn }

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
