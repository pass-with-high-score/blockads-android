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

            val bloomPath = if (filter.bloomUrl.isNotEmpty()) downloadFile(filter.bloomUrl, bloomFile, forceUpdate) else null
            val triePath = if (filter.trieUrl.isNotEmpty()) downloadFile(filter.trieUrl, trieFile, forceUpdate) else null

            if (bloomPath != null && triePath != null) {
                Result.success(DownloadedFilterPaths(bloomPath, triePath))
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
}
