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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Orchestrates the full custom filter flow:
 * 1. Call backend API to compile the filter URL into optimized binaries
 * 2. Download the resulting ZIP file
 * 3. Extract ZIP contents (*.trie, *.bloom, *.css, info.json)
 * 4. Parse info.json for metadata
 * 5. Save FilterList entity to Room DB
 * 6. Copy binary files to the standard remote_filters/ directory
 */
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

    /**
     * Adds a custom filter from a raw filter list URL.
     *
     * @param url The raw filter URL (e.g., "https://example.com/filter.txt")
     * @return The saved [FilterList] entity on success
     * @throws CustomFilterException on API errors
     */
    suspend fun addCustomFilter(url: String, displayName: String? = null): Result<FilterList> = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()

        try {
            // ── Step 0: Check for duplicate ──────────────────────────────
            val existing = filterListDao.getByOriginalUrl(trimmedUrl)
            if (existing != null) {
                return@withContext Result.failure(
                    CustomFilterException("Filter already exists: ${existing.name}")
                )
            }

            // ── Step 1: Call backend API to compile ──────────────────────
            Timber.d("Building custom filter for URL: $trimmedUrl")
            val buildResponse = customFilterApi.buildFilter(trimmedUrl)
            Timber.d("Build success: downloadUrl=${buildResponse.downloadUrl}, rules=${buildResponse.ruleCount}")

            // ── Step 2: Create temp extraction directory ─────────────────
            val sanitizedName = sanitizeName(trimmedUrl)
            val extractDir = File(context.filesDir, "$CUSTOM_FILTERS_DIR/$sanitizedName")
            if (extractDir.exists()) {
                extractDir.deleteRecursively()
            }

            // ── Step 3: Download & extract ZIP ───────────────────────────
            Timber.d("Downloading ZIP to: ${extractDir.absolutePath}")
            val extractedFiles = ZipUtils.downloadAndExtractZip(
                client = client,
                downloadUrl = buildResponse.downloadUrl,
                destDir = extractDir
            )
            Timber.d("Extracted ${extractedFiles.size} files")

            // ── Step 4: Parse info.json ──────────────────────────────────
            val infoJson = extractedFiles.find { it.name == "info.json" }
            val filterInfo = if (infoJson != null && infoJson.exists()) {
                parseInfoJson(infoJson.readText())
            } else {
                // Fallback: derive info from the build response
                FilterInfo(
                    name = deriveFilterName(trimmedUrl),
                    url = trimmedUrl,
                    ruleCount = buildResponse.ruleCount,
                    updatedAt = System.currentTimeMillis().toString()
                )
            }

            val finalName = displayName?.takeIf { it.isNotBlank() } ?: filterInfo.name

            // ── Step 5: Insert entity to get auto-generated ID ──────────
            val filterEntity = FilterList(
                name = finalName,
                url = trimmedUrl,
                description = "Custom filter: $finalName",
                isEnabled = true,
                isBuiltIn = false,
                category = FilterList.CATEGORY_AD,
                ruleCount = filterInfo.ruleCount,
                domainCount = filterInfo.ruleCount, // Set domainCount so UI updates immediately
                bloomUrl = "",   // Will be set after file copy
                trieUrl = "",    // Will be set after file copy
                cssUrl = "",     // Will be set after file copy
                originalUrl = trimmedUrl,
                lastUpdated = System.currentTimeMillis()
            )

            val insertedId = filterListDao.insert(filterEntity)
            Timber.d("Inserted custom filter with id=$insertedId")

            // ── Step 6: Copy binary files to remote_filters/<id>.xxx ────
            val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }

            val trieFile = extractedFiles.find { it.extension == "trie" }
            val bloomFile = extractedFiles.find { it.extension == "bloom" }
            val cssFile = extractedFiles.find { it.extension == "css" }

            trieFile?.let {
                val dest = File(remoteFilterDir, "$insertedId.trie")
                it.copyTo(dest, overwrite = true)
                Timber.d("Copied trie → ${dest.absolutePath}")
            }
            bloomFile?.let {
                val dest = File(remoteFilterDir, "$insertedId.bloom")
                it.copyTo(dest, overwrite = true)
                Timber.d("Copied bloom → ${dest.absolutePath}")
            }
            cssFile?.let {
                val dest = File(remoteFilterDir, "$insertedId.css")
                it.copyTo(dest, overwrite = true)
                Timber.d("Copied css → ${dest.absolutePath}")
            }

            // ── Step 7: Update entity with local file markers ────────────
            // bloomUrl/trieUrl must be non-empty so loadAllEnabledFilters()
            // doesn't skip this filter. FilterDownloadManager checks local
            // files first, so it will find them without downloading.
            val updatedEntity = filterEntity.copy(
                id = insertedId,
                bloomUrl = if (bloomFile != null) "local://$insertedId.bloom" else "",
                trieUrl = if (trieFile != null) "local://$insertedId.trie" else "",
                cssUrl = if (cssFile != null) "local://$insertedId.css" else "",
            )
            filterListDao.update(updatedEntity)

            // ── Step 8: Cleanup extraction directory ─────────────────────
            extractDir.deleteRecursively()
            Timber.d("Custom filter added successfully: ${filterInfo.name} (id=$insertedId)")

            Result.success(updatedEntity)
        } catch (e: CustomFilterException) {
            Timber.e(e, "Custom filter API error, trying local compile")
            addCustomFilterLocally(trimmedUrl, displayName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add custom filter via API, trying local compile")
            addCustomFilterLocally(trimmedUrl, displayName)
        }
    }

    /**
     * Deletes a custom filter's local binary files and DB entry.
     */
    suspend fun deleteCustomFilter(filter: FilterList) = withContext(Dispatchers.IO) {
        try {
            // Delete local binary files
            val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR)
            File(remoteFilterDir, "${filter.id}.trie").delete()
            File(remoteFilterDir, "${filter.id}.bloom").delete()
            File(remoteFilterDir, "${filter.id}.css").delete()

            // Delete DB entry
            filterListDao.delete(filter)
            Timber.d("Deleted custom filter: ${filter.name}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete custom filter: ${filter.name}")
        }
    }

    /**
     * Edits an existing custom filter's name and/or URL.
     * Recompiles binaries if the URL changed.
     */
    suspend fun editCustomFilter(
        filter: FilterList,
        newName: String,
        newUrl: String
    ): Result<FilterList> = withContext(Dispatchers.IO) {
        try {
            val trimmedUrl = newUrl.trim()
            val trimmedName = newName.trim()

            // If URL hasn't changed, just update name.
            if (trimmedUrl == filter.originalUrl || trimmedUrl == filter.url) {
                if (trimmedName != filter.name) {
                    val updated = filter.copy(name = trimmedName, lastUpdated = System.currentTimeMillis())
                    filterListDao.update(updated)
                    return@withContext Result.success(updated)
                }
                return@withContext Result.success(filter)
            }

            // Url changed: Check duplicate
            val existingByUrl = filterListDao.getByUrl(trimmedUrl)
            val existingByOriginalUrl = filterListDao.getByOriginalUrl(trimmedUrl)
            
            if ((existingByUrl != null && existingByUrl.id != filter.id) || 
                (existingByOriginalUrl != null && existingByOriginalUrl.id != filter.id)) {
                return@withContext Result.failure(
                    CustomFilterException("Filter already exists")
                )
            }

            // Re-compile via API
            val buildResponse = customFilterApi.buildFilter(trimmedUrl)

            // Download & extract
            val sanitizedName = sanitizeName(trimmedUrl)
            val extractDir = File(context.filesDir, "$CUSTOM_FILTERS_DIR/$sanitizedName")
            if (extractDir.exists()) extractDir.deleteRecursively()

            val extractedFiles = ZipUtils.downloadAndExtractZip(
                client = client,
                downloadUrl = buildResponse.downloadUrl,
                destDir = extractDir
            )

            // Parse info.json
            val infoJson = extractedFiles.find { it.name == "info.json" }
            val filterInfo = if (infoJson != null && infoJson.exists()) {
                parseInfoJson(infoJson.readText())
            } else {
                null
            }

            val finalName = if (trimmedName.isNotEmpty()) trimmedName else filterInfo?.name ?: deriveFilterName(trimmedUrl)
            val ruleCount = filterInfo?.ruleCount ?: buildResponse.ruleCount

            // Overwrite binary files
            val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }
            val trieFile = extractedFiles.find { it.extension == "trie" }
            val bloomFile = extractedFiles.find { it.extension == "bloom" }
            val cssFile = extractedFiles.find { it.extension == "css" }

            trieFile?.copyTo(File(remoteFilterDir, "${filter.id}.trie"), overwrite = true)
            bloomFile?.copyTo(File(remoteFilterDir, "${filter.id}.bloom"), overwrite = true)
            cssFile?.copyTo(File(remoteFilterDir, "${filter.id}.css"), overwrite = true)

            // Update DB
            val updated = filter.copy(
                name = finalName,
                url = trimmedUrl,
                originalUrl = trimmedUrl,
                ruleCount = ruleCount,
                domainCount = ruleCount,
                bloomUrl = if (bloomFile != null) "local://${filter.id}.bloom" else filter.bloomUrl,
                trieUrl = if (trieFile != null) "local://${filter.id}.trie" else filter.trieUrl,
                cssUrl = if (cssFile != null) "local://${filter.id}.css" else filter.cssUrl,
                lastUpdated = System.currentTimeMillis()
            )
            filterListDao.update(updated)

            // Cleanup
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

    /**
     * Re-compiles and updates an existing custom filter.
     */
    suspend fun updateCustomFilter(filter: FilterList): Result<FilterList> =
        withContext(Dispatchers.IO) {
            val isLocallyBuilt = filter.trieUrl.startsWith("local://") &&
                    filter.bloomUrl.startsWith("local://")

            if (isLocallyBuilt) {
                return@withContext updateCustomFilterLocally(filter)
            }

            try {
                val url = filter.originalUrl.ifEmpty { filter.url }

                // Re-compile via API
                val buildResponse = customFilterApi.buildFilter(url)

                // Download & extract
                val sanitizedName = sanitizeName(url)
                val extractDir = File(context.filesDir, "$CUSTOM_FILTERS_DIR/$sanitizedName")
                if (extractDir.exists()) extractDir.deleteRecursively()

                val extractedFiles = ZipUtils.downloadAndExtractZip(
                    client = client,
                    downloadUrl = buildResponse.downloadUrl,
                    destDir = extractDir
                )

                // Parse info.json
                val infoJson = extractedFiles.find { it.name == "info.json" }
                val ruleCount = if (infoJson != null && infoJson.exists()) {
                    parseInfoJson(infoJson.readText()).ruleCount
                } else {
                    buildResponse.ruleCount
                }

                // Overwrite binary files
                val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR)
                extractedFiles.find { it.extension == "trie" }?.copyTo(
                    File(remoteFilterDir, "${filter.id}.trie"), overwrite = true
                )
                extractedFiles.find { it.extension == "bloom" }?.copyTo(
                    File(remoteFilterDir, "${filter.id}.bloom"), overwrite = true
                )
                extractedFiles.find { it.extension == "css" }?.copyTo(
                    File(remoteFilterDir, "${filter.id}.css"), overwrite = true
                )

                // Update DB
                val updated = filter.copy(
                    ruleCount = ruleCount,
                    domainCount = ruleCount,
                    lastUpdated = System.currentTimeMillis()
                )
                filterListDao.update(updated)

                // Cleanup
                extractDir.deleteRecursively()

                Timber.d("Updated custom filter: ${filter.name}, rules=$ruleCount")
                Result.success(updated)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update custom filter: ${filter.name}")
                Result.failure(CustomFilterException("Update failed: ${e.message}", e))
            }
        }

    /**
     * Re-downloads and re-compiles a locally-built custom filter.
     */
    private suspend fun updateCustomFilterLocally(filter: FilterList): Result<FilterList> {
        val url = filter.originalUrl.ifEmpty { filter.url }
        val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }
        val tempFile = File(context.cacheDir, "filter_update_${System.currentTimeMillis()}.txt")

        return try {
            Timber.d("Local update: downloading $url")
            val response = client.get(url)
            val channel = response.bodyAsChannel()
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }

            val triePath = File(remoteFilterDir, "${filter.id}.trie").absolutePath
            val bloomPath = File(remoteFilterDir, "${filter.id}.bloom").absolutePath

            val ruleCount = tunnel.Tunnel.compileFilterList(
                tempFile.absolutePath, triePath, bloomPath
            ).toInt()

            val updated = filter.copy(
                ruleCount = ruleCount,
                domainCount = ruleCount,
                lastUpdated = System.currentTimeMillis()
            )
            filterListDao.update(updated)

            Timber.d("Local update: ${filter.name}, rules=$ruleCount")
            Result.success(updated)
        } catch (e: Exception) {
            Timber.e(e, "Local update failed: ${filter.name}")
            Result.failure(CustomFilterException("Local update failed: ${e.message}", e))
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Re-compiles an existing custom filter locally (in-place, same DB ID).
     * Used when switching from server to local build mode.
     */
    suspend fun recompileLocally(filter: FilterList): Result<FilterList> = withContext(Dispatchers.IO) {
        val url = filter.originalUrl.ifEmpty { filter.url }
        val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }
        val tempFile = File(context.cacheDir, "filter_recompile_${System.currentTimeMillis()}.txt")

        try {
            Timber.d("Recompile locally: downloading $url")
            val response = client.get(url)
            val channel = response.bodyAsChannel()
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }

            val triePath = File(remoteFilterDir, "${filter.id}.trie").absolutePath
            val bloomPath = File(remoteFilterDir, "${filter.id}.bloom").absolutePath

            val ruleCount = tunnel.Tunnel.compileFilterList(
                tempFile.absolutePath, triePath, bloomPath
            ).toInt()

            val updated = filter.copy(
                ruleCount = ruleCount,
                domainCount = ruleCount,
                bloomUrl = "local://${filter.id}.bloom",
                trieUrl = "local://${filter.id}.trie",
                lastUpdated = System.currentTimeMillis()
            )
            filterListDao.update(updated)

            Timber.d("Recompile locally: ${filter.name}, rules=$ruleCount")
            Result.success(updated)
        } catch (e: Exception) {
            Timber.e(e, "Recompile locally failed: ${filter.name}")
            Result.failure(CustomFilterException("Local recompile failed: ${e.message}", e))
        } finally {
            tempFile.delete()
        }
    }

    // ── Local Compile (fallback when backend is unreachable) ──────────

    /**
     * Downloads a filter list and compiles .trie/.bloom locally using the Go compiler.
     * Used as fallback when the backend API is unreachable.
     */
    suspend fun addCustomFilterLocally(url: String, displayName: String?): Result<FilterList> = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }
        val tempFile = File(context.cacheDir, "filter_download_${System.currentTimeMillis()}.txt")
        // Use a temp ID for compilation, insert DB only after success
        val tempTrieFile = File(context.cacheDir, "compile_${System.currentTimeMillis()}.trie")
        val tempBloomFile = File(context.cacheDir, "compile_${System.currentTimeMillis()}.bloom")

        try {
            // Download filter list to temp file
            Timber.d("Local compile: downloading $trimmedUrl")
            val response = client.get(trimmedUrl)
            val channel = response.bodyAsChannel()
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }

            // Compile to temp files FIRST (before any DB insert)
            val ruleCount = tunnel.Tunnel.compileFilterList(
                tempFile.absolutePath, tempTrieFile.absolutePath, tempBloomFile.absolutePath
            ).toInt()

            Timber.d("Local compile: $ruleCount rules")

            val finalName = displayName?.takeIf { it.isNotBlank() } ?: deriveFilterName(trimmedUrl)

            // Only insert to DB after successful compile
            val filterEntity = FilterList(
                name = finalName,
                url = trimmedUrl,
                description = "Custom filter: $finalName",
                isEnabled = true,
                isBuiltIn = false,
                category = FilterList.CATEGORY_AD,
                ruleCount = ruleCount,
                domainCount = ruleCount,
                bloomUrl = "",
                trieUrl = "",
                cssUrl = "",
                originalUrl = trimmedUrl,
                lastUpdated = System.currentTimeMillis()
            )
            val insertedId = filterListDao.insert(filterEntity)

            // Move compiled files to final location
            val finalTrie = File(remoteFilterDir, "$insertedId.trie")
            val finalBloom = File(remoteFilterDir, "$insertedId.bloom")
            tempTrieFile.copyTo(finalTrie, overwrite = true)
            tempBloomFile.copyTo(finalBloom, overwrite = true)

            // Update entity with local file markers
            val updatedEntity = filterEntity.copy(
                id = insertedId,
                bloomUrl = "local://$insertedId.bloom",
                trieUrl = "local://$insertedId.trie",
            )
            filterListDao.update(updatedEntity)

            Result.success(updatedEntity)
        } catch (e: Exception) {
            Timber.e(e, "Local compile failed")
            Result.failure(CustomFilterException("Local compile failed: ${e.message}", e))
        } finally {
            tempFile.delete()
            tempTrieFile.delete()
            tempBloomFile.delete()
        }
    }

    // ── WorkManager-based local compile ────────────────────────────────

    /**
     * Enqueues a WorkManager job to download and compile a filter locally.
     * Returns immediately — compilation happens in the background with a notification.
     */
    fun enqueueLocalCompile(url: String, name: String) {
        val workRequest = OneTimeWorkRequestBuilder<FilterCompileWorker>()
            .setInputData(FilterCompileWorker.buildInputData(url, name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FilterCompileWorker.WORK_NAME_PREFIX + url.hashCode(),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        Timber.d("Enqueued local compile worker for: $name")
    }

    /**
     * Enqueues a WorkManager job to recompile an existing filter locally.
     */
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
        Timber.d("Enqueued local recompile worker for: ${filter.name}")
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private data class FilterInfo(
        val name: String,
        val url: String,
        val ruleCount: Int,
        val updatedAt: String
    )

    /**
     * Parses the info.json file from the extracted ZIP.
     * Expected format: { "name": "...", "url": "...", "ruleCount": 123, "updatedAt": "..." }
     */
    private fun parseInfoJson(json: String): FilterInfo {
        fun extractString(key: String): String? {
            val pattern = "\"$key\"\\s*:\\s*\"(.*?)\"".toRegex()
            return pattern.find(json)?.groupValues?.get(1)
        }

        fun extractInt(key: String): Int {
            val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
            return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        return FilterInfo(
            name = extractString("name") ?: "Custom Filter",
            url = extractString("url") ?: "",
            ruleCount = extractInt("ruleCount"),
            updatedAt = extractString("updatedAt") ?: ""
        )
    }

    /**
     * Derives a human-readable filter name from the URL.
     */
    private fun deriveFilterName(url: String): String {
        return try {
            val path = url.substringAfterLast("/").substringBeforeLast(".")
            if (path.isNotBlank()) {
                path.replace(Regex("[^a-zA-Z0-9_-]"), " ")
                    .trim()
                    .replaceFirstChar { it.uppercase() }
            } else {
                "Custom Filter"
            }
        } catch (_: Exception) {
            "Custom Filter"
        }
    }

    /**
     * Creates a filesystem-safe name from a URL.
     */
    private fun sanitizeName(url: String): String {
        return url.substringAfterLast("/")
            .substringBeforeLast(".")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(64)
            .ifBlank { "custom_${System.currentTimeMillis()}" }
    }
}
