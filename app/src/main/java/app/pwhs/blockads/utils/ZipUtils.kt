package app.pwhs.blockads.utils

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
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Utility for downloading and extracting ZIP files safely.
 */
object ZipUtils {

    /**
     * Downloads a ZIP file from [downloadUrl] and extracts its contents into [destDir].
     *
     * - Streams directly from network into ZipInputStream (no intermediate file)
     * - Rejects non-2xx responses instead of extracting the error body
     * - Guards against zip-slip path traversal attacks
     * - On failure, removes only the entries this call created
     *
     * @param client Ktor HttpClient for downloading
     * @param downloadUrl URL of the ZIP file
     * @param destDir Directory to extract into (will be created if needed)
     * @return List of extracted [File]s on success
     * @throws ZipExtractionException on any failure
     */
    private const val DOWNLOAD_REQUEST_TIMEOUT_MS = 5 * 60 * 1000L
    private const val DOWNLOAD_SOCKET_TIMEOUT_MS = 60_000L
    private const val PROGRESS_EMIT_INTERVAL_MS = 100L

    fun formatDownloadProgress(downloadedBytes: Long, totalBytes: Long): String {
        val downloadedStr = formatMegabytes(downloadedBytes)
        return if (totalBytes > 0L) {
            "$downloadedStr / ${formatMegabytes(totalBytes)}"
        } else {
            downloadedStr
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 100) {
            String.format(java.util.Locale.US, "%.0f MB", mb)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    suspend fun downloadAndExtractZip(
        client: HttpClient,
        downloadUrl: String,
        destDir: File,
        onProgress: ((Int) -> Unit)? = null,
        onBytesProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): List<File> = withContext(Dispatchers.IO) {
        val extractedFiles = mutableListOf<File>()
        // Everything this call creates, in creation order, so a failed run can be
        // undone without touching files that were already there.
        val createdPaths = LinkedHashSet<File>()

        try {
            ensureDirectory(destDir, createdPaths)
            val canonicalDest = destDir.canonicalPath
            // The separator matters: a sibling directory such as "filter-evil" also
            // starts with the characters of "filter".
            val canonicalDestPrefix = canonicalDest + File.separator

            Timber.d("Downloading ZIP from: $downloadUrl")

            // Stream to temp file with extended timeout so large filter ZIPs don't buffer in RAM or hit 60s timeout
            val tempZipFile = File(destDir, ".download.zip.tmp")
            try {
                client.prepareGet(downloadUrl) {
                    timeout {
                        requestTimeoutMillis = DOWNLOAD_REQUEST_TIMEOUT_MS
                        socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MS
                    }
                }.execute { response ->
                    if (response.status.value !in 200..299) {
                        throw ZipExtractionException(
                            "Unexpected HTTP ${response.status.value} downloading $downloadUrl"
                        )
                    }
                    val contentLength = response.contentLength() ?: -1L
                    val channel = response.bodyAsChannel()
                    var lastEmitMs = 0L

                    FileOutputStream(tempZipFile).use { fos ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesDownloaded = 0L
                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer)
                            if (bytesRead > 0) {
                                fos.write(buffer, 0, bytesRead)
                                bytesDownloaded += bytesRead
                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                    lastEmitMs = now
                                    onBytesProgress?.invoke(bytesDownloaded, contentLength)
                                    if (contentLength > 0) {
                                        val percent = ((bytesDownloaded * 100) / contentLength).toInt().coerceIn(0, 100)
                                        onProgress?.invoke(percent)
                                    }
                                }
                            }
                        }
                        onBytesProgress?.invoke(bytesDownloaded, contentLength)
                        if (contentLength > 0) {
                            onProgress?.invoke(100)
                        }
                    }
                }

                Timber.d("ZIP downloaded: ${tempZipFile.length()} bytes")

                // Extract from the downloaded temp file
                tempZipFile.inputStream().use { fis ->
                    ZipInputStream(fis).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val entryFile = File(destDir, entry.name)

                            // Zip-slip protection
                            if (!entryFile.canonicalPath.startsWith(canonicalDestPrefix)) {
                                throw ZipExtractionException(
                                    "Zip-slip detected: ${entry.name}"
                                )
                            }

                            if (entry.isDirectory) {
                                ensureDirectory(entryFile, createdPaths)
                            } else {
                                ensureDirectory(entryFile.parentFile, createdPaths)
                                createdPaths.add(entryFile)
                                BufferedOutputStream(FileOutputStream(entryFile)).use { bos ->
                                    val buf = ByteArray(4 * 1024)
                                    var len: Int
                                    while (zis.read(buf).also { len = it } > 0) {
                                        bos.write(buf, 0, len)
                                    }
                                }
                                extractedFiles.add(entryFile)
                                Timber.d("Extracted: ${entryFile.name} (${entryFile.length()} bytes)")
                            }

                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            } finally {
                // Always delete temp zip
                tempZipFile.delete()
            }

            if (extractedFiles.isEmpty()) {
                throw ZipExtractionException("ZIP archive was empty")
            }

            Timber.d("Extraction complete: ${extractedFiles.size} files in ${destDir.name}")
            extractedFiles
        } catch (e: ZipExtractionException) {
            // Clean up on our custom exception
            cleanupCreatedPaths(createdPaths)
            throw e
        } catch (e: Exception) {
            // Clean up on any unexpected error
            cleanupCreatedPaths(createdPaths)
            Timber.e(e, "Failed to download/extract ZIP")
            throw ZipExtractionException("Extraction failed: ${e.message}", e)
        }
    }

    /** Creates [dir] and any missing parent, recording what was newly created. */
    private fun ensureDirectory(dir: File?, createdPaths: MutableSet<File>) {
        if (dir == null || dir.isDirectory) return
        generateSequence(dir) { it.parentFile }
            .takeWhile { !it.exists() }
            .toList()
            .asReversed()
            .forEach { if (it.mkdirs() || it.isDirectory) createdPaths.add(it) }
    }

    /** Undoes a failed extraction, newest path first, leaving pre-existing content alone. */
    private fun cleanupCreatedPaths(createdPaths: Set<File>) {
        createdPaths.toList().asReversed().forEach { path ->
            try {
                if (path.delete()) {
                    Timber.d("Cleaned up after failed extraction: ${path.absolutePath}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to clean up: ${path.absolutePath}")
            }
        }
    }
}

/**
 * Exception thrown when ZIP extraction fails.
 */
class ZipExtractionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
