package app.pwhs.blockads.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.remote.api.CustomFilterApi
import app.pwhs.blockads.data.remote.api.CustomFilterException
import app.pwhs.blockads.utils.ZipUtils
import app.pwhs.blockads.worker.FilterCompileWorker
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class CustomFilterManager(
    private val context: Context,
    private val client: HttpClient,
    private val filterListDao: FilterListDao,
    private val customFilterApi: CustomFilterApi
) {
    companion object {
        private const val CUSTOM_FILTERS_DIR = "custom_filters"
        private const val REMOTE_FILTERS_DIR = "remote_filters"
    }

    private val localCompiler = CustomFilterLocalCompiler(context, client, filterListDao)

    suspend fun addCustomFilter(
        url: String,
        displayName: String? = null,
        category: String = FilterList.CATEGORY_AD
    ): Result<FilterList> =
        addCustomFilter(listOf(url), displayName, category)

    suspend fun addCustomFilter(
        urls: List<String>,
        displayName: String? = null,
        category: String = FilterList.CATEGORY_AD,
        onProgress: ((Int) -> Unit)? = null,
        onBytesProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FilterList> = withContext(Dispatchers.IO) {
        val cleanUrls = urls.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanUrls.isEmpty()) {
            return@withContext Result.failure(CustomFilterException("No URLs provided"))
        }
        val combinedUrl = cleanUrls.joinToString("\n")

        try {
            val existing = filterListDao.getByOriginalUrl(combinedUrl)
            if (existing != null) {
                return@withContext Result.failure(
                    CustomFilterException("Filter already exists: ${existing.name}")
                )
            }

            onProgress?.invoke(20)
            Timber.d("Building custom filter for URLs: $cleanUrls")
            val buildResponse = customFilterApi.buildFilter(cleanUrls, displayName?.trim())
            onProgress?.invoke(40)

            val sanitizedName = if (cleanUrls.size == 1) localCompiler.sanitizeName(cleanUrls.first()) else "merged_${System.currentTimeMillis()}"
            val extractDir = File(context.filesDir, "$CUSTOM_FILTERS_DIR/$sanitizedName")
            if (extractDir.exists()) extractDir.deleteRecursively()

            val extractedFiles = ZipUtils.downloadAndExtractZip(
                client = client,
                downloadUrl = buildResponse.downloadUrl,
                destDir = extractDir,
                onProgress = { dl -> onProgress?.invoke(40 + (dl * 45) / 100) },
                onBytesProgress = onBytesProgress
            )
            onProgress?.invoke(90)

            val infoJson = extractedFiles.find { it.name == "info.json" }
            val filterInfo = if (infoJson != null && infoJson.exists()) {
                parseInfoJson(infoJson.readText())
            } else {
                FilterInfo(
                    name = if (cleanUrls.size == 1) localCompiler.deriveFilterName(cleanUrls.first()) else "Merged Filter (${cleanUrls.size})",
                    url = combinedUrl,
                    ruleCount = buildResponse.ruleCount,
                    updatedAt = System.currentTimeMillis().toString()
                )
            }

            val finalName = displayName?.takeIf { it.isNotBlank() } ?: filterInfo.name

            val filterEntity = FilterList(
                name = finalName,
                url = combinedUrl,
                description = if (cleanUrls.size > 1) "Merged from ${cleanUrls.size} filter lists" else "Custom filter: $finalName",
                isEnabled = true,
                isBuiltIn = false,
                category = category.ifBlank { FilterList.CATEGORY_AD },
                ruleCount = filterInfo.ruleCount,
                domainCount = filterInfo.ruleCount,
                bloomUrl = "",
                trieUrl = "",
                cssUrl = "",
                scriptletsUrl = "",
                originalUrl = combinedUrl,
                lastUpdated = System.currentTimeMillis()
            )

            val insertedId = filterListDao.insert(filterEntity)
            val copied = copyExtractedArtifacts(extractedFiles, insertedId)

            val updatedEntity = filterEntity.copy(
                id = insertedId,
                bloomUrl = if (copied.hasBloom) "local://$insertedId.bloom" else "",
                trieUrl = if (copied.hasTrie) "local://$insertedId.trie" else "",
                cssUrl = if (copied.hasCss) "local://$insertedId.css" else "",
                scriptletsUrl = if (copied.hasScriptlets) "local://$insertedId.scriptlets" else "",
            )
            filterListDao.update(updatedEntity)

            extractDir.deleteRecursively()
            Timber.d("Custom filter added successfully: ${filterInfo.name} (id=$insertedId)")
            Result.success(updatedEntity)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add custom filter via API, trying local compile")
            addCustomFilterLocally(cleanUrls, displayName, category, onProgress, onBytesProgress)
        }
    }

    suspend fun deleteCustomFilter(filter: FilterList) = withContext(Dispatchers.IO) {
        try {
            val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR)
            File(remoteFilterDir, "${filter.id}.trie").delete()
            File(remoteFilterDir, "${filter.id}.bloom").delete()
            File(remoteFilterDir, "${filter.id}.css").delete()
            File(remoteFilterDir, "${filter.id}.scriptlets").delete()
            File(remoteFilterDir, "${filter.id}.modules.json").delete()
            File(remoteFilterDir, "${filter.id}.ips").delete()
            File(remoteFilterDir, "${filter.id}.info.json").delete()
            File(remoteFilterDir, "${filter.id}.modules.json").delete()

            filterListDao.delete(filter)
            Timber.d("Deleted custom filter: ${filter.name}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete custom filter: ${filter.name}")
        }
    }

    suspend fun editCustomFilter(
        filter: FilterList,
        newName: String,
        newUrl: String,
        newCategory: String? = null,
        onProgress: ((Int) -> Unit)? = null,
        onBytesProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FilterList> = withContext(Dispatchers.IO) {
        try {
            val trimmedUrl = newUrl.trim()
            val trimmedName = newName.trim()
            val finalCategory = newCategory?.trim()?.ifBlank { filter.category } ?: filter.category

            if (trimmedUrl == filter.originalUrl || trimmedUrl == filter.url) {
                if (trimmedName != filter.name || finalCategory != filter.category) {
                    val updated = filter.copy(
                        name = trimmedName,
                        category = finalCategory,
                        lastUpdated = System.currentTimeMillis()
                    )
                    filterListDao.update(updated)
                    onProgress?.invoke(100)
                    return@withContext Result.success(updated)
                }
                onProgress?.invoke(100)
                return@withContext Result.success(filter)
            }

            val existingByUrl = filterListDao.getByUrl(trimmedUrl)
            val existingByOriginalUrl = filterListDao.getByOriginalUrl(trimmedUrl)
            if ((existingByUrl != null && existingByUrl.id != filter.id) ||
                (existingByOriginalUrl != null && existingByOriginalUrl.id != filter.id)
            ) {
                return@withContext Result.failure(CustomFilterException("Filter already exists"))
            }

            onProgress?.invoke(20)
            val cleanUrls = trimmedUrl.lines().map { it.trim() }.filter { it.isNotBlank() }
            val buildResponse = customFilterApi.buildFilter(cleanUrls, trimmedName.ifBlank { filter.name })
            onProgress?.invoke(40)

            val sanitizedName = if (cleanUrls.size == 1) localCompiler.sanitizeName(cleanUrls.first()) else "merged_${filter.id}"
            val extractDir = File(context.filesDir, "$CUSTOM_FILTERS_DIR/$sanitizedName")
            if (extractDir.exists()) extractDir.deleteRecursively()

            val extractedFiles = ZipUtils.downloadAndExtractZip(
                client = client,
                downloadUrl = buildResponse.downloadUrl,
                destDir = extractDir,
                onProgress = { dl -> onProgress?.invoke(40 + (dl * 45) / 100) },
                onBytesProgress = onBytesProgress
            )
            onProgress?.invoke(90)

            val infoJson = extractedFiles.find { it.name == "info.json" }
            val filterInfo = if (infoJson != null && infoJson.exists()) parseInfoJson(infoJson.readText()) else null

            val finalName = trimmedName.ifEmpty { filterInfo?.name ?: localCompiler.deriveFilterName(trimmedUrl) }
            val ruleCount = filterInfo?.ruleCount ?: buildResponse.ruleCount
            val copied = copyExtractedArtifacts(extractedFiles, filter.id)

            val updated = filter.copy(
                name = finalName,
                url = trimmedUrl,
                originalUrl = trimmedUrl,
                category = finalCategory,
                ruleCount = ruleCount,
                domainCount = ruleCount,
                bloomUrl = if (copied.hasBloom) "local://${filter.id}.bloom" else filter.bloomUrl,
                trieUrl = if (copied.hasTrie) "local://${filter.id}.trie" else filter.trieUrl,
                cssUrl = if (copied.hasCss) "local://${filter.id}.css" else filter.cssUrl,
                scriptletsUrl = if (copied.hasScriptlets) "local://${filter.id}.scriptlets" else filter.scriptletsUrl,
                lastUpdated = System.currentTimeMillis()
            )
            filterListDao.update(updated)
            extractDir.deleteRecursively()

            Timber.d("Edited custom filter: $finalName, newUrl=$trimmedUrl")
            Result.success(updated)
        } catch (e: CustomFilterException) {
            Timber.e(e, "Custom filter API error on edit")
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "Failed to edit custom filter")
            Result.failure(CustomFilterException("Failed to edit filter: ${e.message}", e))
        }
    }

    suspend fun updateCustomFilter(filter: FilterList): Result<FilterList> =
        withContext(Dispatchers.IO) {
            val isLocallyBuilt = filter.trieUrl.startsWith("local://") &&
                filter.bloomUrl.startsWith("local://")

            if (isLocallyBuilt) {
                return@withContext localCompiler.recompileOrUpdateLocally(filter)
            }

            try {
                val rawUrl = filter.originalUrl.ifEmpty { filter.url }
                val urls = rawUrl.lines().map { it.trim() }.filter { it.isNotBlank() }
                val buildResponse = customFilterApi.buildFilter(urls, filter.name)

                val sanitizedName = if (urls.size == 1) localCompiler.sanitizeName(urls.first()) else "merged_${filter.id}"
                val extractDir = File(context.filesDir, "$CUSTOM_FILTERS_DIR/$sanitizedName")
                if (extractDir.exists()) extractDir.deleteRecursively()

                val extractedFiles = ZipUtils.downloadAndExtractZip(
                    client = client,
                    downloadUrl = buildResponse.downloadUrl,
                    destDir = extractDir
                )

                val infoJson = extractedFiles.find { it.name == "info.json" }
                val ruleCount = if (infoJson != null && infoJson.exists()) {
                    parseInfoJson(infoJson.readText()).ruleCount
                } else {
                    buildResponse.ruleCount
                }

                val copied = copyExtractedArtifacts(extractedFiles, filter.id)

                val updated = filter.copy(
                    ruleCount = ruleCount,
                    domainCount = ruleCount,
                    cssUrl = if (copied.hasCss) "local://${filter.id}.css" else filter.cssUrl,
                    scriptletsUrl = if (copied.hasScriptlets) "local://${filter.id}.scriptlets" else filter.scriptletsUrl,
                    lastUpdated = System.currentTimeMillis()
                )
                filterListDao.update(updated)
                extractDir.deleteRecursively()

                Timber.d("Updated custom filter: ${filter.name}, rules=$ruleCount")
                Result.success(updated)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update custom filter: ${filter.name}")
                Result.failure(CustomFilterException("Update failed: ${e.message}", e))
            }
        }

    suspend fun recompileLocally(filter: FilterList): Result<FilterList> =
        localCompiler.recompileOrUpdateLocally(filter)

    suspend fun addCustomFilterLocally(
        urls: List<String>,
        displayName: String?,
        category: String = FilterList.CATEGORY_AD,
        onProgress: ((Int) -> Unit)? = null,
        onBytesProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FilterList> =
        localCompiler.addCustomFilterLocally(urls, displayName, category, onProgress, onBytesProgress)

    suspend fun addCustomFilterLocally(
        url: String,
        displayName: String?,
        category: String = FilterList.CATEGORY_AD
    ): Result<FilterList> =
        localCompiler.addCustomFilterLocally(listOf(url), displayName, category)

    suspend fun enqueueLocalCompile(
        url: String,
        name: String,
        category: String = FilterList.CATEGORY_AD
    ) {
        val placeholder = FilterList(
            name = name,
            url = url,
            description = "Compiling…",
            isEnabled = true,
            isBuiltIn = false,
            category = category.ifBlank { FilterList.CATEGORY_AD },
            ruleCount = 0,
            domainCount = 0,
            bloomUrl = "local://pending.bloom",
            trieUrl = "local://pending.trie",
            originalUrl = url,
            lastUpdated = System.currentTimeMillis()
        )
        val insertedId = filterListDao.insert(placeholder)
        val workRequest = OneTimeWorkRequestBuilder<FilterCompileWorker>()
            .setInputData(FilterCompileWorker.buildInputData(url, name, insertedId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FilterCompileWorker.WORK_NAME_PREFIX + insertedId,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun enqueueRecompileLocally(filter: FilterList) {
        val url = filter.originalUrl.ifEmpty { filter.url }
        val workRequest = OneTimeWorkRequestBuilder<FilterCompileWorker>()
            .setInputData(FilterCompileWorker.buildInputData(url, filter.name, filter.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FilterCompileWorker.WORK_NAME_PREFIX + filter.id,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private data class CopiedArtifacts(
        val hasTrie: Boolean,
        val hasBloom: Boolean,
        val hasCss: Boolean,
        val hasScriptlets: Boolean
    )

    private fun copyExtractedArtifacts(extractedFiles: List<File>, filterId: Long): CopiedArtifacts {
        val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }
        val trieFile = extractedFiles.find { it.extension == "trie" }
        val bloomFile = extractedFiles.find { it.extension == "bloom" }
        val cssFile = extractedFiles.find { it.extension == "css" }
        val scriptletsFile = extractedFiles.find { it.extension == "scriptlets" }
        val modulesFile = extractedFiles.find { it.name.endsWith(".modules.json") }
        val ipsFile = extractedFiles.find { it.extension == "ips" }
        val infoFile = extractedFiles.find { it.name == "info.json" }

        trieFile?.copyTo(File(remoteFilterDir, "$filterId.trie"), overwrite = true)
        bloomFile?.copyTo(File(remoteFilterDir, "$filterId.bloom"), overwrite = true)
        cssFile?.copyTo(File(remoteFilterDir, "$filterId.css"), overwrite = true)
        scriptletsFile?.copyTo(File(remoteFilterDir, "$filterId.scriptlets"), overwrite = true)
        modulesFile?.copyTo(File(remoteFilterDir, "$filterId.modules.json"), overwrite = true)
        ipsFile?.copyTo(File(remoteFilterDir, "$filterId.ips"), overwrite = true)
        infoFile?.copyTo(File(remoteFilterDir, "$filterId.info.json"), overwrite = true)

        return CopiedArtifacts(
            hasTrie = trieFile != null,
            hasBloom = bloomFile != null,
            hasCss = cssFile != null,
            hasScriptlets = scriptletsFile != null
        )
    }

    internal data class FilterInfo(
        val name: String,
        val url: String,
        val ruleCount: Int,
        val updatedAt: String
    )

    internal fun parseInfoJson(json: String): FilterInfo {
        return runCatching {
            val obj = org.json.JSONObject(json)
            val baseRuleCount = obj.optInt("ruleCount", 0)
            val statsTotal = obj.optJSONObject("stats")?.let { s ->
                s.optInt("domains", 0) +
                    s.optInt("ips", 0) +
                    s.optInt("cosmetic", 0) +
                    s.optInt("cosmeticExceptions", 0) +
                    s.optInt("injectCss", 0) +
                    s.optInt("scriptlets", 0) +
                    s.optInt("scriptletExceptions", 0) +
                    s.optInt("blockPaths", 0) +
                    s.optInt("removeParams", 0)
            } ?: 0
            FilterInfo(
                name = obj.optString("name", "Custom Filter").ifBlank { "Custom Filter" },
                url = obj.optString("url", ""),
                ruleCount = maxOf(baseRuleCount, statsTotal),
                updatedAt = obj.optString("updatedAt", "")
            )
        }.getOrElse {
            fun extractString(key: String): String? =
                "\"$key\"\\s*:\\s*\"(.*?)\"".toRegex().find(json)?.groupValues?.get(1)

            fun extractInt(key: String): Int =
                "\"$key\"\\s*:\\s*(\\d+)".toRegex().find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            FilterInfo(
                name = extractString("name") ?: "Custom Filter",
                url = extractString("url") ?: "",
                ruleCount = extractInt("ruleCount"),
                updatedAt = extractString("updatedAt") ?: ""
            )
        }
    }
}
