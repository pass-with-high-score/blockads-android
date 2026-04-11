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
            "https://raw.githubusercontent.com/pass-with-high-score/blockads-default-filter/refs/heads/main/output/filter_lists.json"
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

    fun getCosmeticCssPath(): String? = null

    // Simplified blocking checks - Go engine handles most blocking via tries
    fun isBlocked(domain: String): Boolean = false
    fun hasCustomRule(domain: String): Long = -1L
    fun getBlockReason(domain: String): String = ""

    suspend fun seedDefaultsIfNeeded() {
        fetchAndSyncRemoteFilterLists()
    }

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
                    filterListDao.update(
                        existing.copy(
                            url = remote.originalUrl ?: "",
                            description = remote.description ?: "",
                            category = category,
                            bloomUrl = remote.bloomUrl,
                            trieUrl = remote.trieUrl,
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

    suspend fun loadAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
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
                    Timber.d("Downloading filter: ${filter.name} (bloom=${filter.bloomUrl.take(60)}, trie=${filter.trieUrl.take(60)})")
                    val result = downloadManager.downloadFilterList(filter, forceUpdate = false)
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

                _domainCountFlow.value = totalCount
                Result.success(totalCount)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load filters")
                Result.failure(e)
            }
        }
    }
}
