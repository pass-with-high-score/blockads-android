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
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI

internal class CustomFilterLocalCompiler(
    private val context: Context,
    private val client: HttpClient,
    private val filterListDao: FilterListDao
) {
    companion object {
        private const val REMOTE_FILTERS_DIR = "remote_filters"
        private const val INCLUDE_PREFIX = "!#include "
        private const val MAX_INCLUDE_DEPTH = 5
        private const val MAX_INCLUDES_PER_LIST = 32
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
        val tempIpsFile = File("${tempBase.absolutePath}.ips")

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
            if (tempIpsFile.exists() && tempIpsFile.length() > 0) {
                tempIpsFile.copyTo(File(remoteFilterDir, "$insertedId.ips"), overwrite = true)
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
            tempIpsFile.delete()
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
        BufferedOutputStream(destFile.outputStream(), 64 * 1024).use { output ->
            for ((index, u) in urls.withIndex()) {
                val visited = mutableSetOf<String>()
                val includeBudget = intArrayOf(MAX_INCLUDES_PER_LIST)
                downloadUrlWithIncludes(
                    url = u,
                    rootOrigin = extractOrigin(u),
                    depth = 0,
                    visited = visited,
                    includeBudget = includeBudget,
                    output = output,
                    urlIndex = index,
                    totalUrls = urls.size,
                    onProgress = onProgress,
                    onBytesProgress = onBytesProgress
                )
            }
        }
    }

    private suspend fun downloadUrlWithIncludes(
        url: String,
        rootOrigin: String?,
        depth: Int,
        visited: MutableSet<String>,
        includeBudget: IntArray,
        output: OutputStream,
        urlIndex: Int,
        totalUrls: Int,
        onProgress: ((Int) -> Unit)?,
        onBytesProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        val normalizedUrl = normalizeUrl(url)
        if (!visited.add(normalizedUrl)) {
            Timber.w("Skipping cyclic/duplicate !#include URL: $url")
            return
        }

        Timber.d("Downloading raw filter (depth=$depth): $url")
        val pendingIncludes = mutableListOf<String>()
        val lineBuffer = ByteArrayOutputStream(256)

        client.prepareGet(url) {
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
                    var segmentStart = 0
                    for (i in 0 until read) {
                        val b = buffer[i]
                        if (b == '\n'.code.toByte()) {
                            if (lineBuffer.size() < 2048) {
                                lineBuffer.write(buffer, segmentStart, i - segmentStart)
                            }
                            val lineStr = lineBuffer.toString(Charsets.UTF_8.name()).trim()
                            lineBuffer.reset()

                            val includeTarget = parseIncludeDirective(lineStr)
                            if (includeTarget != null) {
                                resolveSameOriginInclude(url, rootOrigin, includeTarget)?.let {
                                    pendingIncludes.add(it)
                                }
                            } else {
                                output.write(lineStr.toByteArray(Charsets.UTF_8))
                                output.write('\n'.code)
                            }
                            segmentStart = i + 1
                        }
                    }
                    if (segmentStart < read) {
                        val remaining = read - segmentStart
                        if (lineBuffer.size() + remaining < 2048) {
                            lineBuffer.write(buffer, segmentStart, remaining)
                        } else {
                            // Flush oversized lines directly without buffering
                            if (lineBuffer.size() > 0) {
                                output.write(lineBuffer.toByteArray())
                                lineBuffer.reset()
                            }
                            output.write(buffer, segmentStart, remaining)
                        }
                    }

                    bytesDownloaded += read
                    if (depth == 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs >= 100L) {
                            lastEmitMs = now
                            onBytesProgress?.invoke(bytesDownloaded, contentLength)
                            if (contentLength > 0) {
                                val filePct = ((bytesDownloaded * 100) / contentLength).toInt().coerceIn(0, 100)
                                val overallPct = 10 + (((urlIndex * 100 + filePct) * 70) / (totalUrls * 100))
                                onProgress?.invoke(overallPct)
                            }
                        }
                    }
                }
            }

            if (lineBuffer.size() > 0) {
                val tailLine = lineBuffer.toString(Charsets.UTF_8.name()).trim()
                lineBuffer.reset()
                val includeTarget = parseIncludeDirective(tailLine)
                if (includeTarget != null) {
                    resolveSameOriginInclude(url, rootOrigin, includeTarget)?.let {
                        pendingIncludes.add(it)
                    }
                } else if (tailLine.isNotEmpty()) {
                    output.write(tailLine.toByteArray(Charsets.UTF_8))
                    output.write('\n'.code)
                }
            }

            if (depth == 0) {
                onBytesProgress?.invoke(bytesDownloaded, contentLength)
            }
        }

        if (depth >= MAX_INCLUDE_DEPTH || pendingIncludes.isEmpty()) return

        for (incUrl in pendingIncludes) {
            if (includeBudget[0] <= 0) {
                Timber.w("Reached max !#include limit ($MAX_INCLUDES_PER_LIST), skipping: $incUrl")
                break
            }
            includeBudget[0]--
            try {
                downloadUrlWithIncludes(
                    url = incUrl,
                    rootOrigin = rootOrigin,
                    depth = depth + 1,
                    visited = visited,
                    includeBudget = includeBudget,
                    output = output,
                    urlIndex = urlIndex,
                    totalUrls = totalUrls,
                    onProgress = null,
                    onBytesProgress = null
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to resolve !#include: $incUrl")
            }
        }
    }

    internal fun parseIncludeDirective(trimmedLine: String): String? {
        if (!trimmedLine.startsWith(INCLUDE_PREFIX, ignoreCase = true)) return null
        val target = trimmedLine.substring(INCLUDE_PREFIX.length).trim()
        return target.takeIf { it.isNotEmpty() && !it.contains(' ') }
    }

    internal fun resolveSameOriginInclude(baseUrl: String, rootOrigin: String?, target: String): String? {
        return try {
            val baseUri = URI(baseUrl)
            val resolved = baseUri.resolve(target).normalize()
            val scheme = resolved.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val resolvedOrigin = extractOrigin(resolved.toString())
            if (rootOrigin == null || resolvedOrigin != rootOrigin) {
                Timber.w("Blocked cross-origin !#include: $resolved (root origin: $rootOrigin)")
                return null
            }
            resolved.toString()
        } catch (e: Exception) {
            Timber.w(e, "Invalid !#include target: $target")
            null
        }
    }

    private fun extractOrigin(url: String): String? {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = when {
                uri.port != -1 -> uri.port
                scheme == "https" -> 443
                scheme == "http" -> 80
                else -> -1
            }
            "$scheme://$host:$port"
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUrl(url: String): String {
        return try {
            URI(url).normalize().toString()
        } catch (_: Exception) {
            url
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
