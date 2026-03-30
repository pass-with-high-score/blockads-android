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
    val cssPath: String?
)

class FilterDownloadManager(
    private val context: Context,
    private val client: HttpClient
) {
    private val filterDir = File(context.filesDir, "remote_filters").apply { 
        if (!exists()) mkdirs() 
    }

    /**
     * Downloads the required filter files (.bloom, .trie, and optional .css).
     * @param filter The FilterList to download.
     * @param forceUpdate Forces re-download even if the file exists locally.
     * @return Result containing the local paths to the downloaded files.
     */
    suspend fun downloadFilterList(
        filter: FilterList,
        forceUpdate: Boolean = false
    ): Result<DownloadedFilterPaths> = withContext(Dispatchers.IO) {
        try {
            val bloomFile = File(filterDir, "${filter.id}.bloom")
            val trieFile = File(filterDir, "${filter.id}.trie")
            val cssFile = File(filterDir, "${filter.id}.css")

            val bloomPath = if (filter.bloomUrl.isNotEmpty()) downloadFile(filter.bloomUrl, bloomFile, forceUpdate) else null
            val triePath = if (filter.trieUrl.isNotEmpty()) downloadFile(filter.trieUrl, trieFile, forceUpdate) else null

            var cssPath: String? = null
            if (filter.cssUrl.isNotEmpty()) {
                cssPath = downloadFile(filter.cssUrl, cssFile, forceUpdate)
            }

            if (bloomPath != null && triePath != null) {
                Result.success(DownloadedFilterPaths(bloomPath, triePath, cssPath))
            } else {
                Result.failure(Exception("Failed to download core filter files (.bloom or .trie) for ${filter.id}"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error downloading filter list ${filter.id}")
            Result.failure(e)
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
     * Reads a downloaded CSS/Adguard file containing rules and returns them as a single string.
     * The Go engine now handles parsing Adguard/ABP formatting and domain specificity.
     */
    fun getInjectableCss(file: File): String {
        if (!file.exists() || file.length() == 0L) {
            return ""
        }

        return try {
            file.readText()
        } catch (e: Exception) {
            Timber.e(e, "Error reading CSS file ${file.absolutePath}")
            ""
        }
    }
}
