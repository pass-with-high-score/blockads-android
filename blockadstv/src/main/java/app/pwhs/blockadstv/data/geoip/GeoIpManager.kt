package app.pwhs.blockadstv.data.geoip

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

data class GeoIpDatabaseInfo(
    val isAvailable: Boolean,
    val recordCount: Int,
    val fileSizeBytes: Long,
    val lastModified: Long,
)

class GeoIpManager(
    private val context: Context,
    private val client: HttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    companion object {
        const val GEOIP_FILE_NAME = "geoip_ipv4.bin"
        private const val GEOIP_TMP_NAME = "geoip_ipv4.bin.tmp"

        const val PRIMARY_GZ_URL =
            "https://github.com/pass-with-high-score/blockads-android/releases/download/geoip-database/geoip_ipv4.bin.gz"
        const val FALLBACK_BIN_URL =
            "https://github.com/pass-with-high-score/blockads-android/releases/download/geoip-database/geoip_ipv4.bin"

        private const val UPDATE_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val _updateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updateEvents: SharedFlow<Unit> = _updateEvents.asSharedFlow()

    fun getDatabaseFile(): File = File(context.filesDir, GEOIP_FILE_NAME)

    fun getDatabaseInfo(): GeoIpDatabaseInfo {
        val file = getDatabaseFile()
        val available = file.exists() && file.length() > 0
        val size = if (available) file.length() else 0L
        val count = (size / 10).toInt()
        val lastMod = if (available) file.lastModified() else 0L
        return GeoIpDatabaseInfo(
            isAvailable = available,
            recordCount = count,
            fileSizeBytes = size,
            lastModified = lastMod,
        )
    }

    suspend fun checkAndUpdate(customUrl: String? = null, force: Boolean = false): Result<Boolean> =
        withContext(ioDispatcher) {
            val targetFile = getDatabaseFile()
            val isAvailable = targetFile.exists() && targetFile.length() > 0
            val url = customUrl?.trim()?.takeIf { it.isNotEmpty() }

            if (!force && isAvailable && url == null) {
                val age = System.currentTimeMillis() - targetFile.lastModified()
                if (age < UPDATE_THRESHOLD_MS) {
                    Timber.d("GeoIP database is fresh, skipping download")
                    return@withContext Result.success(false)
                }
            }

            Timber.i("Starting GeoIP database download (force=$force)")
            val tmpFile = File(context.filesDir, GEOIP_TMP_NAME)

            val downloaded = if (url != null) {
                tryDownloadCustom(url, tmpFile)
            } else {
                tryDownloadGz(PRIMARY_GZ_URL, tmpFile) || tryDownloadRaw(FALLBACK_BIN_URL, tmpFile)
            }

            if (!downloaded) {
                tmpFile.delete()
                val err = "Failed to download GeoIP database"
                Timber.w(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            if (tmpFile.length() < 1000 || tmpFile.length() % 10 != 0L) {
                tmpFile.delete()
                val err = "Downloaded GeoIP database is invalid or misaligned (${tmpFile.length()} bytes)"
                Timber.w(err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }

            Timber.i("GeoIP database successfully updated: ${targetFile.length()} bytes")
            GeoIpLookup.reload(context)
            _updateEvents.tryEmit(Unit)
            Result.success(true)
        }

    private suspend fun tryDownloadCustom(url: String, tmpFile: File): Boolean {
        return if (url.endsWith(".gz", ignoreCase = true)) {
            tryDownloadGz(url, tmpFile)
        } else {
            tryDownloadRaw(url, tmpFile)
        }
    }

    private suspend fun tryDownloadGz(url: String, tmpFile: File): Boolean {
        return runCatching {
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                Timber.w("Failed to fetch $url: HTTP ${response.status.value}")
                return false
            }
            response.bodyAsChannel().toInputStream().use { networkStream ->
                GZIPInputStream(networkStream).use { gzStream ->
                    FileOutputStream(tmpFile).use { outStream ->
                        gzStream.copyTo(outStream)
                    }
                }
            }
            true
        }.getOrElse { error ->
            Timber.w(error, "Failed to download/decompress GeoIP from $url")
            false
        }
    }

    private suspend fun tryDownloadRaw(url: String, tmpFile: File): Boolean {
        return runCatching {
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                Timber.w("Failed to fetch $url: HTTP ${response.status.value}")
                return false
            }
            response.bodyAsChannel().toInputStream().use { networkStream ->
                FileOutputStream(tmpFile).use { outStream ->
                    networkStream.copyTo(outStream)
                }
            }
            true
        }.getOrElse { error ->
            Timber.w(error, "Failed to download raw GeoIP from $url")
            false
        }
    }
}
