package app.pwhs.blockads.data.remote

import android.content.Context
import app.pwhs.blockads.data.entities.FilterList
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

data class DownloadedFilterPaths(
    val bloomPath: String?,
    val triePath: String?,
    val cssPath: String?,
    val scriptletPath: String?
)

class FilterDownloadManager(
    private val context: Context,
    private val client: HttpClient
) {
    private val filterDir = File(context.filesDir, "remote_filters").apply { 
        if (!exists()) mkdirs() 
    }

    /**
     * Downloads the required filter files (.bloom, .trie, and optional .css / .scriptlets).
     * Automatically handles .zip archives if downloadUrl is provided.
     */
    suspend fun downloadFilterList(
        filter: FilterList,
        forceUpdate: Boolean = false
    ): Result<DownloadedFilterPaths> = withContext(Dispatchers.IO) {
        try {
            val bloomFile = File(filterDir, "${filter.id}.bloom")
            val trieFile = File(filterDir, "${filter.id}.trie")
            val cssFile = File(filterDir, "${filter.id}.css")
            val scriptletFile = File(filterDir, "${filter.id}.scriptlets")

            if (!forceUpdate && bloomFile.exists() && bloomFile.length() > 0 && trieFile.exists() && trieFile.length() > 0) {
                Timber.d("Filter ${filter.id} already cached locally")
                return@withContext Result.success(
                    DownloadedFilterPaths(
                        bloomPath = bloomFile.absolutePath,
                        triePath = trieFile.absolutePath,
                        cssPath = cssFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                        scriptletPath = scriptletFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
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
                val zipSuccess = downloadAndExtractZip(zipUrl, bloomFile, trieFile, cssFile, scriptletFile)
                if (zipSuccess && bloomFile.exists() && trieFile.exists()) {
                    return@withContext Result.success(
                        DownloadedFilterPaths(
                            bloomPath = bloomFile.absolutePath,
                            triePath = trieFile.absolutePath,
                            cssPath = cssFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                            scriptletPath = scriptletFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
                        )
                    )
                }
            }

            val bloomPath = if (filter.bloomUrl.isNotEmpty()) downloadFile(filter.bloomUrl, bloomFile, forceUpdate) else null
            val triePath = if (filter.trieUrl.isNotEmpty()) downloadFile(filter.trieUrl, trieFile, forceUpdate) else null

            var cssPath: String? = null
            if (filter.cssUrl.isNotEmpty()) {
                cssPath = downloadFile(filter.cssUrl, cssFile, forceUpdate)
            }

            var scriptletPath: String? = null
            if (filter.scriptletsUrl.isNotEmpty()) {
                scriptletPath = downloadFile(filter.scriptletsUrl, scriptletFile, forceUpdate)
            }

            if (bloomPath != null && triePath != null) {
                Result.success(DownloadedFilterPaths(bloomPath, triePath, cssPath, scriptletPath))
            } else {
                Result.failure(Exception("Failed to download core filter files (.bloom or .trie) for ${filter.id}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error downloading filter list ${filter.id}")
            Result.failure(e)
        }
    }

    private suspend fun downloadAndExtractZip(
        url: String,
        bloomFile: File,
        trieFile: File,
        cssFile: File,
        scriptletFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        val tempZip = File(filterDir, "temp_${System.currentTimeMillis()}.zip")
        try {
            Timber.d("Downloading filter zip from $url")
            val response = client.get(url)
            if (response.status.value !in 200..299) {
                Timber.e("HTTP ${response.status.value} downloading zip $url")
                return@withContext false
            }

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
                    val targetFile = when {
                        entry.name.endsWith(".bloom") -> bloomFile
                        entry.name.endsWith(".trie") -> trieFile
                        entry.name.endsWith(".css") -> cssFile
                        entry.name.endsWith(".scriptlets") -> scriptletFile
                        else -> null
                    }
                    targetFile?.let { out ->
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(out).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            Timber.d("Successfully extracted zip for filter")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to download and extract filter zip: $url")
            false
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Downloads a single file from the given URL and saves it to [destFile].
     * Uses a temporary file during download to prevent partial corruption.
     */
    private suspend fun downloadFile(url: String, destFile: File, forceUpdate: Boolean): String? {
        // Custom filters use "local://" sentinel URLs — files are already on disk
        if (url.startsWith("local://")) {
            return if (destFile.exists() && destFile.length() > 0) destFile.absolutePath else null
        }

        if (!forceUpdate && destFile.exists() && destFile.length() > 0) {
            Timber.d("File already exists: ${destFile.name}")
            return destFile.absolutePath
        }

        return try {
            Timber.d("Downloading from $url to ${destFile.name}")
            val response = client.get(url)
            if (response.status.value !in 200..299) {
                Timber.e("HTTP ${response.status.value} downloading $url")
                return null
            }
            val channel = response.bodyAsChannel()

            val tempFile = File(destFile.parent, "${destFile.name}.tmp")
            withContext(Dispatchers.IO) {
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (channel.readAvailable(buffer).also { bytesRead = it } >= 0) {
                        if (bytesRead > 0) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            if (tempFile.renameTo(destFile)) {
                Timber.d("Successfully downloaded to ${destFile.absolutePath}")
                destFile.absolutePath
            } else {
                Timber.e("Failed to rename temp file to ${destFile.name}")
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download $url")
            null
        }
    }

    /**
     * Reads a downloaded CSS file containing raw selectors, appends { display: none !important; }
     * and returns a single valid CSS string ready for injection.
     */
    fun getInjectableCss(file: File): String {
        if (!file.exists() || file.length() == 0L) {
            return ""
        }

        val cssBuilder = StringBuilder()
        try {
            file.forEachLine { line ->
                var selector = line.trim()
                if (selector.isEmpty() || selector.startsWith("!") || (selector.startsWith("#") && !selector.startsWith("##"))) {
                    return@forEachLine
                }
                if (selector.startsWith("##")) {
                    selector = selector.removePrefix("##").trim()
                }
                // Skip unhandled domain-specific rules (e.g. domain.com##...) or complex rules
                if (selector.isNotEmpty() && !selector.contains("##")) {
                    cssBuilder.append(selector).append(" { display: none !important; }\n")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error reading CSS file ${file.absolutePath}")
            return ""
        }
        return cssBuilder.toString()
    }
}
