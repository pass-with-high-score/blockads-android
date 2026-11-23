package app.pwhs.blockads.data.repository

import android.content.Context
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FilterList
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Handles seeding of default and AdGuard-referenced filter lists from bundled assets
 * and syncing with the remote compiler API.
 */
class FilterListSeeder(
    private val context: Context,
    private val filterListDao: FilterListDao,
    private val client: HttpClient,
    private val appPreferences: AppPreferences? = null
) {
    companion object {
        private const val FILTER_LIST_JSON_URL = "https://complier.pwhs.app/api/filters/default"
        private const val BUNDLED_ASSET_PATH = "preset/default_filters.json"
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private suspend fun isStoreRestricted(allowInStore: Boolean): Boolean {
        if (!app.pwhs.blockads.BuildConfig.IS_STORE) return false
        if (allowInStore) return false
        return appPreferences?.filter?.storeUseDefaultFilters?.first() != true
    }

    suspend fun seedDefaultsIfNeeded(allowInStore: Boolean = false) = withContext(Dispatchers.IO) {
        if (isStoreRestricted(allowInStore)) {
            val builtIns = filterListDao.getAllSync().filter { it.isBuiltIn }
            if (builtIns.isNotEmpty()) {
                builtIns.forEach { filterListDao.delete(it) }
                Timber.d("Store flavor: deleted ${builtIns.size} built-in filters")
            }
            return@withContext
        }
        val existingBuiltIns = filterListDao.getAllSync().count { it.isBuiltIn }
        if (existingBuiltIns < 90) {
            seedFromBundledAsset(allowInStore)
            fetchAndSyncRemoteFilterLists(allowInStore)
        }
    }

    suspend fun seedFromBundledAsset(allowInStore: Boolean = false) = withContext(Dispatchers.IO) {
        if (isStoreRestricted(allowInStore)) {
            return@withContext
        }
        try {
            val assetJson = context.assets.open(BUNDLED_ASSET_PATH).bufferedReader().use { it.readText() }
            val bundledLists = parseFilterJson(assetJson)
            if (bundledLists.isNotEmpty()) {
                syncFilters(bundledLists, allowDelete = false)
                Timber.d("Seeded ${bundledLists.size} filters from bundled asset")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to seed from bundled asset: $BUNDLED_ASSET_PATH")
        }
    }

    suspend fun fetchAndSyncRemoteFilterLists(allowInStore: Boolean = false) = withContext(Dispatchers.IO) {
        if (isStoreRestricted(allowInStore)) {
            return@withContext
        }
        try {
            val channel = client.get(FILTER_LIST_JSON_URL).bodyAsChannel()
            val buffer = ByteArray(256 * 1024)
            val output = ByteArrayOutputStream()
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read > 0) output.write(buffer, 0, read)
            }
            val jsonString = output.toString(Charsets.UTF_8.name())
            val remoteLists = parseFilterJson(jsonString)
            if (remoteLists.isNotEmpty()) {
                val bundledNames = loadBundledNames()
                syncFilters(remoteLists, allowDelete = true, protectedNames = bundledNames)
                Timber.d("Synced ${remoteLists.size} filters from remote JSON")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch remote filter list JSON: ${e.message}")
        }
    }

    private fun loadBundledNames(): Set<String> {
        return try {
            val assetJson = context.assets.open(BUNDLED_ASSET_PATH).bufferedReader().use { it.readText() }
            parseFilterJson(assetJson).map { it.name }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun syncFilters(
        incoming: List<app.pwhs.blockads.data.remote.models.FilterList>,
        allowDelete: Boolean,
        protectedNames: Set<String> = emptySet()
    ) {
        val existingLists = filterListDao.getAllSync()
        val existingByName = existingLists.associateBy { it.name }

        for (item in incoming) {
            val existing = existingByName[item.name]
            val cat = item.category ?: FilterList.CATEGORY_AD
            val originalUrl = item.originalUrl ?: existing?.originalUrl ?: ""
            val zipUrl = item.downloadUrl ?: existing?.url ?: ""

            val filterId = if (existing != null) {
                val needsUpdate = existing.category != cat ||
                    (originalUrl.isNotEmpty() && existing.originalUrl != originalUrl) ||
                    (item.bloomUrl.isNotEmpty() && existing.bloomUrl != item.bloomUrl) ||
                    (item.trieUrl.isNotEmpty() && existing.trieUrl != item.trieUrl) ||
                    (item.ruleCount > 0 && existing.ruleCount != item.ruleCount) ||
                    (!item.description.isNullOrEmpty() && existing.description != item.description)

                if (needsUpdate) {
                    filterListDao.update(
                        existing.copy(
                            url = if (zipUrl.isNotEmpty()) zipUrl else existing.url,
                            description = item.description ?: existing.description,
                            category = cat,
                            bloomUrl = if (item.bloomUrl.isNotEmpty()) item.bloomUrl else existing.bloomUrl,
                            trieUrl = if (item.trieUrl.isNotEmpty()) item.trieUrl else existing.trieUrl,
                            domainCount = if (item.ruleCount > 0) item.ruleCount else existing.domainCount,
                            cssUrl = item.cssUrl ?: existing.cssUrl,
                            scriptletsUrl = item.scriptletsUrl ?: existing.scriptletsUrl,
                            ruleCount = if (item.ruleCount > 0) item.ruleCount else existing.ruleCount,
                            originalUrl = if (originalUrl.isNotEmpty()) originalUrl else existing.originalUrl,
                            isBuiltIn = true
                        )
                    )
                }
                existing.id
            } else {
                filterListDao.insert(
                    FilterList(
                        name = item.name,
                        url = if (zipUrl.isNotEmpty()) zipUrl else originalUrl,
                        description = item.description ?: "",
                        isEnabled = item.isEnabled,
                        isBuiltIn = true,
                        category = cat,
                        ruleCount = item.ruleCount,
                        domainCount = item.ruleCount,
                        bloomUrl = item.bloomUrl,
                        trieUrl = item.trieUrl,
                        cssUrl = item.cssUrl ?: "",
                        scriptletsUrl = item.scriptletsUrl ?: "",
                        originalUrl = originalUrl
                    )
                )
            }

            item.stats?.let { stats ->
                saveFilterStats(filterId, stats)
            }
        }

        if (allowDelete && protectedNames.isNotEmpty()) {
            val incomingNames = incoming.map { it.name }.toSet()
            val obsolete = existingLists.filter {
                it.isBuiltIn && it.name !in incomingNames && it.name !in protectedNames
            }
            for (o in obsolete) {
                filterListDao.delete(o)
                Timber.d("Removed obsolete built-in filter: ${o.name}")
            }
        }
    }

    internal fun parseFilterJson(json: String): List<app.pwhs.blockads.data.remote.models.FilterList> {
        return try {
            jsonParser.decodeFromString<List<app.pwhs.blockads.data.remote.models.FilterList>>(json)
        } catch (e: Exception) {
            Timber.w(e, "Kotlinx JSON parsing failed, using fallback parser")
            parseWithFallback(json)
        }
    }

    private fun parseWithFallback(json: String): List<app.pwhs.blockads.data.remote.models.FilterList> {
        val results = mutableListOf<app.pwhs.blockads.data.remote.models.FilterList>()
        val objects = json.split("},").map {
            it.trim().removePrefix("[").removeSuffix("]").trim() + "}"
        }

        for (obj in objects) {
            val cleaned = obj.trim().removePrefix("{").removeSuffix("}").removeSuffix("},")
            if (cleaned.isBlank()) continue

            fun extractString(key: String): String? {
                val pattern = "\"$key\"\\s*:\\s*\"(.*?)\"".toRegex()
                return pattern.find(cleaned)?.groupValues?.get(1)?.replace("\\u0026", "&")
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
            val bloomUrl = extractString("bloomUrl") ?: ""
            val trieUrl = extractString("trieUrl") ?: ""

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
                    originalUrl = extractString("originalUrl"),
                    downloadUrl = extractString("downloadUrl")
                )
            )
        }
        return results
    }

    private fun saveFilterStats(filterId: Long, stats: app.pwhs.blockads.data.remote.models.FilterStats) {
        runCatching {
            val dir = java.io.File(context.filesDir, "remote_filters")
            val isDownloaded = java.io.File(dir, "$filterId.trie").exists() || java.io.File(dir, "$filterId.bloom").exists()
            val infoFile = java.io.File(dir, "$filterId.info.json")
            if (!isDownloaded || !infoFile.exists() || infoFile.length() == 0L) {
                if (!dir.exists()) dir.mkdirs()
                val statsJson = org.json.JSONObject().apply {
                    put("stats", org.json.JSONObject().apply {
                        put("domains", stats.domains)
                        put("ips", stats.ips)
                        put("cosmetic", stats.cosmetic)
                        put("cosmeticExceptions", stats.cosmeticExceptions)
                        put("injectCss", stats.injectCss)
                        put("scriptlets", stats.scriptlets)
                        put("scriptletExceptions", stats.scriptletExceptions)
                        put("blockPaths", stats.blockPaths)
                        put("removeParams", stats.removeParams)
                    })
                }
                infoFile.writeText(statsJson.toString())
            }
        }
    }
}
