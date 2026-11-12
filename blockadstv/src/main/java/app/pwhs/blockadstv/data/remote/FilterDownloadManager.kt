package app.pwhs.blockadstv.data.remote

import android.content.Context
import app.pwhs.blockadstv.data.entities.FilterList
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class FilterDownloadManager(
    private val context: Context,
    private val client: HttpClient,
) {
    private val filterDir = File(context.filesDir, "remote_filters").apply {
        if (!exists()) mkdirs()
    }

    suspend fun downloadFilterList(
        filter: FilterList,
        forceUpdate: Boolean = false,
    ): Result<DownloadedFilterPaths> = withContext(Dispatchers.IO) {
        try {
            val bloomFile = File(filterDir, "${filter.id}.bloom")
            val trieFile = File(filterDir, "${filter.id}.trie")
            val cssFile = File(filterDir, "${filter.id}.css")
            val scriptletFile = File(filterDir, "${filter.id}.scriptlets")
            val modulesFile = File(filterDir, "${filter.id}.modules.json")

            if (!forceUpdate && bloomFile.exists() && bloomFile.length() > 0 && trieFile.exists() && trieFile.length() > 0) {
                Timber.d("Filter ${filter.id} already cached locally")
                return@withContext Result.success(
                    DownloadedFilterPaths(
                        bloomPath = bloomFile.absolutePath,
                        triePath = trieFile.absolutePath,
                        cssPath = cssFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                        scriptletPath = scriptletFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                        modulesPath = modulesFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                    )
                )
            }

            val zipUrl = when {
                filter.url.contains(".zip") -> filter.url
                filter.bloomUrl.contains(".bloom") -> filter.bloomUrl.replace(".bloom", ".zip")
                filter.trieUrl.contains(".trie") -> filter.trieUrl.replace(".trie", ".zip")
                else -> ""
            }

            if (zipUrl.isNotEmpty()) {
                val tempZip = File(filterDir, "temp_${System.currentTimeMillis()}.zip")
                try {
                    val response = client.get(zipUrl)
                    if (response.status.value in 200..299) {
                        val channel = response.bodyAsChannel()
                        FileOutputStream(tempZip).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var bytesRead: Int
                            while (channel.readAvailable(buffer).also { bytesRead = it } >= 0) {
                                if (bytesRead > 0) output.write(buffer, 0, bytesRead)
                            }
                        }
                        java.util.zip.ZipFile(tempZip).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                val target = when {
                                    entry.name.endsWith(".bloom") -> bloomFile
                                    entry.name.endsWith(".trie") -> trieFile
                                    entry.name.endsWith(".css") -> cssFile
                                    entry.name.endsWith(".scriptlets") -> scriptletFile
                                    entry.name.endsWith(".modules.json") -> modulesFile
                                    else -> null
                                }
                                target?.let { out ->
                                    zip.getInputStream(entry).use { input ->
                                        FileOutputStream(out).use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                        }
                        if (bloomFile.exists() && trieFile.exists()) {
                            return@withContext Result.success(
                                DownloadedFilterPaths(
                                    bloomPath = bloomFile.absolutePath,
                                    triePath = trieFile.absolutePath,
                                    cssPath = cssFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                                    scriptletPath = scriptletFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                                    modulesPath = modulesFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error extracting zip for ${filter.id}")
                } finally {
                    tempZip.delete()
                }
            }

            val bloomPath = if (filter.bloomUrl.isNotEmpty()) downloadFile(filter.bloomUrl, bloomFile, forceUpdate) else null
            val triePath = if (filter.trieUrl.isNotEmpty()) downloadFile(filter.trieUrl, trieFile, forceUpdate) else null
            val cssPath = if (filter.cssUrl.isNotEmpty()) downloadFile(filter.cssUrl, cssFile, forceUpdate) else null
            val scriptletPath = if (filter.scriptletsUrl.isNotEmpty()) downloadFile(filter.scriptletsUrl, scriptletFile, forceUpdate) else null

            if (bloomPath != null && triePath != null) {
                Result.success(DownloadedFilterPaths(bloomPath, triePath, cssPath, scriptletPath))
            } else {
                Result.failure(Exception("Failed to download core filter files for ${filter.id}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error downloading filter list ${filter.id}")
            Result.failure(e)
        }
    }

    private suspend fun downloadFile(url: String, destFile: File, forceUpdate: Boolean): String? {
        if (url.startsWith("local://")) {
            return if (destFile.exists() && destFile.length() > 0) destFile.absolutePath else null
        }

        if (!forceUpdate && destFile.exists() && destFile.length() > 0) {
            Timber.d("Cache hit: ${destFile.name} (${destFile.length()} bytes)")
            return destFile.absolutePath
        }

        return try {
            Timber.d("Downloading: $url")
            val response = client.get(url)
            if (response.status.value !in 200..299) {
                Timber.e("HTTP ${response.status.value} downloading $url")
                return null
            }
            val channel = response.bodyAsChannel()

            val tempFile = File(destFile.parent, "${destFile.name}.tmp")
            var totalBytes = 0L
            withContext(Dispatchers.IO) {
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (channel.readAvailable(buffer).also { bytesRead = it } >= 0) {
                        if (bytesRead > 0) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                    }
                }
            }

            // Verify file is not suspiciously small (error page or empty)
            if (totalBytes < 100) {
                Timber.e("Downloaded file too small ($totalBytes bytes), likely an error: ${destFile.name}")
                tempFile.delete()
                return null
            }

            if (tempFile.renameTo(destFile)) {
                Timber.d("Downloaded ${destFile.name}: $totalBytes bytes")
                destFile.absolutePath
            } else {
                Timber.e("Failed to rename temp file: ${destFile.name}")
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download $url")
            null
        }
    }

    fun getInjectableCss(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        val cssBuilder = StringBuilder()
        try {
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("!") || (trimmed.startsWith("#") && !trimmed.startsWith("##"))) {
                    return@forEachLine
                }
                if (trimmed.contains("#@#") || trimmed.contains("##+js") || trimmed.contains("##^")) {
                    return@forEachLine
                }
                when {
                    trimmed.contains("##") -> cssBuilder.append(trimmed).append("\n")
                    trimmed.contains("{") -> cssBuilder.append(trimmed).append("\n")
                    else -> cssBuilder.append("##").append(trimmed).append("\n")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error reading CSS file ${file.absolutePath}")
            return ""
        }
        return cssBuilder.toString()
    }
}
