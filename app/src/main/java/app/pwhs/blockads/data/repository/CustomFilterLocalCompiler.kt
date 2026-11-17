package app.pwhs.blockads.data.repository

import android.content.Context
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.remote.api.CustomFilterException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

internal class CustomFilterLocalCompiler(
    private val context: Context,
    private val client: HttpClient,
    private val filterListDao: FilterListDao
) {
    companion object {
        private const val REMOTE_FILTERS_DIR = "remote_filters"
    }

    suspend fun addCustomFilterLocally(
        urls: List<String>,
        displayName: String?,
        category: String = FilterList.CATEGORY_AD,
        onProgress: ((Int) -> Unit)? = null,
        onBytesProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<FilterList> = withContext(Dispatchers.IO) {
        val cleanUrls = urls.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanUrls.isEmpty()) {
            return@withContext Result.failure(CustomFilterException("URL cannot be empty"))
        }
        val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val tempFile = File(context.cacheDir, "filter_download_$ts.txt")
        val tempBase = File(context.cacheDir, "compile_$ts")
        val tempTrieFile = File("${tempBase.absolutePath}.trie")
        val tempBloomFile = File("${tempBase.absolutePath}.bloom")
        val tempCssFile = File("${tempBase.absolutePath}.css")
        val tempScriptletsFile = File("${tempBase.absolutePath}.scriptlets")
        val tempModulesFile = File("${tempBase.absolutePath}.modules.json")

        try {
            downloadRawFilterUrls(cleanUrls, tempFile, onProgress, onBytesProgress)
            onProgress?.invoke(85)

            val ruleCount = tunnel.Tunnel.compileFilterList(
                tempFile.absolutePath, tempTrieFile.absolutePath, tempBloomFile.absolutePath
            ).toInt()
            onProgress?.invoke(95)

            Timber.d("Local compile: $ruleCount rules")

            val combinedUrl = cleanUrls.joinToString("\n")
            val finalName = displayName?.takeIf { it.isNotBlank() }
                ?: if (cleanUrls.size > 1) "Merged Filter (${cleanUrls.size})" else deriveFilterName(cleanUrls.first())

            val filterEntity = FilterList(
                name = finalName,
                url = combinedUrl,
                description = "Custom filter: $finalName",
                isEnabled = true,
                isBuiltIn = false,
                category = category.ifBlank { FilterList.CATEGORY_AD },
                ruleCount = ruleCount,
                domainCount = ruleCount,
                bloomUrl = "",
                trieUrl = "",
                cssUrl = "",
                scriptletsUrl = "",
                originalUrl = combinedUrl,
                lastUpdated = System.currentTimeMillis()
            )
            val insertedId = filterListDao.insert(filterEntity)

            tempTrieFile.copyTo(File(remoteFilterDir, "$insertedId.trie"), overwrite = true)
            tempBloomFile.copyTo(File(remoteFilterDir, "$insertedId.bloom"), overwrite = true)
            val hasCss = tempCssFile.exists() && tempCssFile.length() > 0
            if (hasCss) {
                tempCssFile.copyTo(File(remoteFilterDir, "$insertedId.css"), overwrite = true)
            }
            val hasScriptlets = tempScriptletsFile.exists() && tempScriptletsFile.length() > 0
            if (hasScriptlets) {
                tempScriptletsFile.copyTo(File(remoteFilterDir, "$insertedId.scriptlets"), overwrite = true)
            }
            if (tempModulesFile.exists() && tempModulesFile.length() > 0) {
                tempModulesFile.copyTo(File(remoteFilterDir, "$insertedId.modules.json"), overwrite = true)
            }

            val updatedEntity = filterEntity.copy(
                id = insertedId,
                bloomUrl = "local://$insertedId.bloom",
                trieUrl = "local://$insertedId.trie",
                cssUrl = if (hasCss) "local://$insertedId.css" else "",
                scriptletsUrl = if (hasScriptlets) "local://$insertedId.scriptlets" else ""
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
            tempCssFile.delete()
            tempScriptletsFile.delete()
            tempModulesFile.delete()
        }
    }

    suspend fun recompileOrUpdateLocally(filter: FilterList): Result<FilterList> = withContext(Dispatchers.IO) {
        val rawUrl = filter.originalUrl.ifEmpty { filter.url }
        val urls = rawUrl.lines().map { it.trim() }.filter { it.isNotBlank() }
        val remoteFilterDir = File(context.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }
        val tempFile = File(context.cacheDir, "filter_recompile_${System.currentTimeMillis()}.txt")

        try {
            downloadRawFilterUrls(urls, tempFile)

            val trieFile = File(remoteFilterDir, "${filter.id}.trie")
            val bloomFile = File(remoteFilterDir, "${filter.id}.bloom")
            val cssFile = File(remoteFilterDir, "${filter.id}.css")
            val scriptletsFile = File(remoteFilterDir, "${filter.id}.scriptlets")

            val ruleCount = tunnel.Tunnel.compileFilterList(
                tempFile.absolutePath, trieFile.absolutePath, bloomFile.absolutePath
            ).toInt()

            val updated = filter.copy(
                ruleCount = ruleCount,
                domainCount = ruleCount,
                bloomUrl = "local://${filter.id}.bloom",
                trieUrl = "local://${filter.id}.trie",
                cssUrl = if (cssFile.exists() && cssFile.length() > 0) "local://${filter.id}.css" else "",
                scriptletsUrl = if (scriptletsFile.exists() && scriptletsFile.length() > 0) "local://${filter.id}.scriptlets" else "",
                lastUpdated = System.currentTimeMillis()
            )
            filterListDao.update(updated)

            Timber.d("Local compile/update: ${filter.name}, rules=$ruleCount")
            Result.success(updated)
        } catch (e: Exception) {
            Timber.e(e, "Local compile/update failed: ${filter.name}")
            Result.failure(CustomFilterException("Local compile failed: ${e.message}", e))
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun downloadRawFilterUrls(
        urls: List<String>,
        destFile: File,
        onProgress: ((Int) -> Unit)? = null,
        onBytesProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ) {
        destFile.outputStream().use { output ->
            for ((index, u) in urls.withIndex()) {
                Timber.d("Downloading raw filter: $u")
                client.prepareGet(u) {
                    timeout {
                        requestTimeoutMillis = 5 * 60 * 1000L
                        socketTimeoutMillis = 60_000L
                    }
                }.execute { response ->
                    val contentLength = response.contentLength() ?: -1L
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    var bytesDownloaded = 0L
                    var lastEmitMs = 0L
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            bytesDownloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 100L) {
                                lastEmitMs = now
                                onBytesProgress?.invoke(bytesDownloaded, contentLength)
                                if (contentLength > 0) {
                                    val filePct = ((bytesDownloaded * 100) / contentLength).toInt().coerceIn(0, 100)
                                    val overallPct = 10 + (((index * 100 + filePct) * 70) / (urls.size * 100))
                                    onProgress?.invoke(overallPct)
                                }
                            }
                        }
                    }
                    onBytesProgress?.invoke(bytesDownloaded, contentLength)
                }
                output.write("\n".toByteArray())
            }
        }
    }

    fun deriveFilterName(url: String): String {
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

    fun sanitizeName(url: String): String {
        return url.substringAfterLast("/")
            .substringBeforeLast(".")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(64)
            .ifBlank { "custom_${System.currentTimeMillis()}" }
    }
}
