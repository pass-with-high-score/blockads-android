package app.pwhs.blockadstv.data.repository

import android.content.Context
import app.pwhs.blockadstv.data.dao.FilterListDao
import app.pwhs.blockadstv.data.entities.FilterList
import app.pwhs.blockadstv.data.remote.FilterDownloadManager
import app.pwhs.blockadstv.data.remote.RemoteFilterList
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class FilterListRepository(
    private val context: Context,
    private val filterListDao: FilterListDao,
    private val client: HttpClient,
    private val downloadManager: FilterDownloadManager,
) {

    companion object {
        private const val FILTER_LIST_JSON_URL =
            "https://complier.pwhs.app/api/filters/default"
        private const val CACHE_DIR = "filter_cache"
    }

    @Volatile
    private var adTriePaths: String = ""

    @Volatile
    private var securityTriePaths: String = ""

    @Volatile
    private var adBloomPaths: String = ""

    @Volatile
    private var securityBloomPaths: String = ""

    private val loadMutex = Mutex()

    private val _domainCountFlow = MutableStateFlow(0)
    val domainCountFlow: StateFlow<Int> = _domainCountFlow.asStateFlow()

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

    // Simplified blocking checks - Go engine handles most blocking via tries
    fun isBlocked(domain: String): Boolean = false
    fun hasCustomRule(domain: String): Long = -1L
    fun getBlockReason(domain: String): String = ""

    suspend fun seedDefaultsIfNeeded() = withContext(Dispatchers.IO) {
        if (app.pwhs.blockadstv.BuildConfig.IS_STORE) {
            val builtIns = filterListDao.getAllSync().filter { it.isBuiltIn }
            if (builtIns.isNotEmpty()) {
                builtIns.forEach { filterListDao.delete(it) }
                Timber.d("Store flavor: deleted ${builtIns.size} built-in filters")
            }
            return@withContext
        }
        fetchAndSyncRemoteFilterLists()
    }

    suspend fun fetchAndSyncRemoteFilterLists() = withContext(Dispatchers.IO) {
        if (app.pwhs.blockadstv.BuildConfig.IS_STORE) {
            return@withContext
        }
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
                    filterListDao.update(
                        existing.copy(
                            url = remote.originalUrl ?: "",
                            description = remote.description ?: "",
                            category = category,
                            bloomUrl = remote.bloomUrl,
                            trieUrl = remote.trieUrl,
                            cssUrl = remote.cssUrl ?: "",
                            scriptletsUrl = remote.scriptletsUrl ?: "",
                            domainCount = remote.ruleCount,
                            ruleCount = remote.ruleCount,
                            originalUrl = remote.originalUrl ?: existing.originalUrl,
                            isBuiltIn = true,
                        )
                    )
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
                            originalUrl = remote.originalUrl ?: "",
                        )
                    )
                }
            }

            val remoteNames = remoteLists.map { it.name }.toSet()
            val obsolete = existingLists.filter { it.isBuiltIn && it.name !in remoteNames }
            for (o in obsolete) {
                filterListDao.delete(o)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch remote filter list JSON")
        }
    }

    private fun parseRemoteFilterJson(json: String): List<RemoteFilterList> {
        return try {
            val results = mutableListOf<RemoteFilterList>()
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
                    RemoteFilterList(
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
                        originalUrl = extractString("originalUrl"),
                    )
                )
            }
            results
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse remote filter JSON")
            emptyList()
        }
    }

    /**
     * Force re-download all enabled filter files, bypassing cache.
     * Use when files may be corrupt from a previous partial download.
     */
    suspend fun forceReloadAllFilters(): Result<Int> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            try {
                // Delete all cached filter files
                val filterDir = File(context.filesDir, "remote_filters")
                if (filterDir.exists()) {
                    filterDir.listFiles()?.forEach { it.delete() }
                    Timber.d("Cleared filter cache: ${filterDir.absolutePath}")
                }

                // Re-sync from remote
                fetchAndSyncRemoteFilterLists()
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear and re-sync filters")
            }
        }
        // Now download fresh files (loadMutex released, loadAllEnabledFilters will re-acquire)
        loadAllEnabledFilters(forceDownload = true)
    }

    suspend fun loadAllEnabledFilters(forceDownload: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            try {
                val enabledLists = filterListDao.getEnabled()
                Timber.d("Enabled filters: ${enabledLists.size}")
                if (enabledLists.isEmpty()) {
                    Timber.d("No enabled filters, clearing all paths")
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

                val adTrieSb = StringBuilder()
                val secTrieSb = StringBuilder()
                val adBloomSb = StringBuilder()
                val secBloomSb = StringBuilder()
                var totalCount = 0

                for (filter in enabledLists) {
                    if (filter.bloomUrl.isEmpty() || filter.trieUrl.isEmpty()) {
                        Timber.d("Skipping ${filter.name}: empty bloomUrl or trieUrl")
                        continue
                    }
                    Timber.d("Downloading filter: ${filter.name} (force=$forceDownload)")
                    val result = downloadManager.downloadFilterList(filter, forceUpdate = forceDownload)
                    if (result.isSuccess) {
                        val paths = result.getOrNull() ?: continue
                        Timber.d("  OK: ${filter.name} bloom=${paths.bloomPath}, trie=${paths.triePath}")
                        if (filter.category == FilterList.CATEGORY_SECURITY) {
                            paths.triePath?.let { secTrieSb.append(it).append(",") }
                            paths.bloomPath?.let { secBloomSb.append(it).append(",") }
                        } else {
                            paths.triePath?.let { adTrieSb.append(it).append(",") }
                            paths.bloomPath?.let { adBloomSb.append(it).append(",") }
                        }
                        if (filter.domainCount != filter.ruleCount) {
                            filterListDao.updateStats(
                                id = filter.id,
                                count = filter.ruleCount,
                                timestamp = System.currentTimeMillis(),
                            )
                        }
                        totalCount += filter.ruleCount
                    } else {
                        Timber.e("  FAILED: ${filter.name} - ${result.exceptionOrNull()?.message}")
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
                val outFile = File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt")
                if (validLists.isEmpty()) {
                    outFile.delete()
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
                val finalCssFile = File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css")
                if (validLists.isEmpty()) {
                    finalCssFile.delete()
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
                    finalCssFile.parentFile?.mkdirs()
                    finalCssFile.writeText(cssBuilder.toString())
                    Timber.d("Wrote cosmetic CSS (${cssBuilder.length} bytes)")
                } else {
                    finalCssFile.delete()
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
                        json.decodeFromString<app.pwhs.blockadstv.data.models.ModuleRulesPackage>(modFile.readText())
                    }.getOrNull() ?: continue

                    mergedGlobalPaths.addAll(pkg.globalBlockPaths)
                    for ((domain, rules) in pkg.domains) {
                        val pair = mergedDomains.getOrPut(domain.lowercase()) { mutableSetOf<String>() to mutableSetOf() }
                        pair.first.addAll(rules.css)
                        pair.second.addAll(rules.blockPaths)
                    }
                }

                if (mergedDomains.isNotEmpty() || mergedGlobalPaths.isNotEmpty()) {
                    val outPkg = app.pwhs.blockadstv.data.models.ModuleRulesPackage(
                        name = "merged_filters",
                        ruleCount = mergedDomains.size + mergedGlobalPaths.size,
                        domains = mergedDomains.mapValues { (_, pair) ->
                            app.pwhs.blockadstv.data.models.DomainModuleRules(
                                css = pair.first.toList(),
                                blockPaths = pair.second.toList(),
                            )
                        },
                        globalBlockPaths = mergedGlobalPaths.toList(),
                    )
                    outFile.parentFile?.mkdirs()
                    outFile.writeText(
                        json.encodeToString(app.pwhs.blockadstv.data.models.ModuleRulesPackage.serializer(), outPkg)
                    )
                    Timber.d("Wrote filter modules (${mergedDomains.size} domains, ${mergedGlobalPaths.size} global paths)")
                } else {
                    outFile.delete()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to compile filter module rules")
            }
        }
}
