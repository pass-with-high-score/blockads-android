package app.pwhs.blockads.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.pwhs.blockads.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

class GitHubUpdateController(
    private val context: Context,
    private val httpClient: HttpClient,
    private val prefs: UpdatePreferencesStore,
    private val modulePrefix: String = "app-",
) : AppUpdateController {

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val _dialogVisible = MutableStateFlow(false)
    override val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()
    private val _bannerDismissed = MutableStateFlow(false)
    override val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()
    override val supportsInAppUpdate: Boolean = true
    override val supportsChannelSelection: Boolean = true

    private var lastReleasePageUrl: String = ""

    override suspend fun checkForUpdate(channel: UpdateChannel, manual: Boolean) {
        if (_state.value is UpdateState.Downloading) return

        if (!manual) {
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLastUpdateCheckMs()
            if (now - lastCheck < AUTO_CHECK_COOLDOWN_MS) return
        }

        _state.value = UpdateState.Checking
        val releases = runCatching {
            withContext(Dispatchers.IO) {
                val response = httpClient.get(RELEASES_API_URL) {
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                    header(HttpHeaders.UserAgent, "BlockAds-Android/${BuildConfig.VERSION_NAME}")
                }
                if (!response.status.isSuccess()) {
                    error("GitHub API returned HTTP ${response.status.value}")
                }
                json.decodeFromString<List<GitHubRelease>>(response.bodyAsText())
            }
        }.getOrElse { error ->
            Timber.w(error, "GitHub update check failed")
            if (manual) {
                _state.value = UpdateState.Error(error.message ?: "Unable to check for updates")
                _dialogVisible.value = true
            } else {
                _state.value = UpdateState.Idle
            }
            return
        }

        prefs.setLastUpdateCheckMs(System.currentTimeMillis())

        val candidate = releases
            .filter { VersionComparator.matchesChannel(it, channel) }
            .filter { release ->
                VersionComparator.selectApkAsset(
                    assets = release.assets,
                    modulePrefix = modulePrefix,
                    supportedAbis = Build.SUPPORTED_ABIS,
                ) != null
            }
            .firstOrNull { release ->
                VersionComparator.isNewerRelease(
                    release = release,
                    channel = channel,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    currentBuildTimeMs = BuildConfig.BUILD_TIME_MS,
                )
            }

        if (candidate == null) {
            _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
            return
        }

        val skipKey = if (channel == UpdateChannel.NIGHTLY) {
            "${candidate.tagName}:${candidate.publishedAt}"
        } else {
            candidate.tagName
        }

        if (!manual && prefs.getSkippedUpdateVersion() == skipKey) {
            _state.value = UpdateState.Idle
            return
        }

        val asset = VersionComparator.selectApkAsset(
            assets = candidate.assets,
            modulePrefix = modulePrefix,
            supportedAbis = Build.SUPPORTED_ABIS,
        )

        if (asset == null) {
            if (manual) {
                _state.value = UpdateState.Error("No compatible APK asset found for ${candidate.tagName}")
                _dialogVisible.value = true
            } else {
                _state.value = UpdateState.Idle
            }
            return
        }

        lastReleasePageUrl = candidate.htmlUrl
        val info = UpdateInfo(
            versionName = candidate.name.ifBlank { candidate.tagName },
            tagName = skipKey,
            changelog = candidate.body,
            downloadUrl = asset.browserDownloadUrl,
            assetName = asset.name,
            assetSizeBytes = asset.size,
            publishedAtMillis = VersionComparator.parseIso8601Millis(candidate.publishedAt),
            isNightly = channel == UpdateChannel.NIGHTLY,
            releasePageUrl = candidate.htmlUrl,
        )

        _bannerDismissed.value = false
        val cachedApk = findValidCachedApk(info)
        if (cachedApk != null) {
            cleanupOldApks(updatesDir(), keepFileName = cachedApk.name)
            _state.value = UpdateState.Downloaded(
                filePath = cachedApk.absolutePath,
                info = info,
            )
        } else {
            _state.value = UpdateState.Available(info)
        }
        _dialogVisible.value = manual || info.isImmediate
    }

    override suspend fun startUpdate(activity: Activity) {
        val current = _state.value
        if (current is UpdateState.Downloading) return

        if (current is UpdateState.Downloaded) {
            val cached = File(current.filePath)
            if (isValidApkFile(cached, current.info?.assetSizeBytes ?: 0L)) {
                installApk(activity, cached)
                return
            }
            val fallbackInfo = current.info ?: run {
                _state.value = UpdateState.Error("Update file is missing. Please check for updates again.")
                _dialogVisible.value = true
                return
            }
            downloadAndInstall(activity, fallbackInfo)
            return
        }

        val info = (current as? UpdateState.Available)?.info ?: return
        val cachedApk = findValidCachedApk(info)
        if (cachedApk != null) {
            lastReleasePageUrl = info.releasePageUrl
            _state.value = UpdateState.Downloaded(cachedApk.absolutePath, info)
            installApk(activity, cachedApk)
            return
        }

        downloadAndInstall(activity, info)
    }

    private suspend fun downloadAndInstall(activity: Activity, info: UpdateInfo) {
        lastReleasePageUrl = info.releasePageUrl
        _dialogVisible.value = true

        val updatesDir = updatesDir().apply { mkdirs() }
        val targetFile = cachedApkFile(updatesDir, info)
        val tempFile = File(updatesDir, "${targetFile.name}.part")
        cleanupOldApks(updatesDir, keepFileName = targetFile.name)
        tempFile.delete()

        runCatching {
            withContext(Dispatchers.IO) {
                httpClient.prepareGet(info.downloadUrl) {
                    header(HttpHeaders.UserAgent, "BlockAds-Android/${BuildConfig.VERSION_NAME}")
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        error("Download failed with HTTP ${response.status.value}")
                    }
                    val totalBytes = response.contentLength() ?: info.assetSizeBytes
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L

                    tempFile.outputStream().use { output ->
                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer)
                            if (bytesRead <= 0) break
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            val progress = if (totalBytes > 0L) {
                                (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            _state.value = UpdateState.Downloading(
                                progress = progress,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes,
                            )
                        }
                    }
                }
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }
        }.onSuccess {
            _state.value = UpdateState.Downloaded(targetFile.absolutePath, info)
            installApk(activity, targetFile)
        }.onFailure { error ->
            Timber.e(error, "APK download failed")
            tempFile.delete()
            _state.value = UpdateState.Error(error.message ?: "APK download failed")
            _dialogVisible.value = true
        }
    }

    override fun completeUpdate() = Unit

    override fun openUpdateDialog() {
        _dialogVisible.value = true
    }

    override fun dismissBanner() {
        _bannerDismissed.value = true
    }

    override suspend fun dismissUpdate(skipVersion: Boolean) {
        val current = _state.value
        _dialogVisible.value = false
        if (skipVersion) {
            val tagToSkip = when (current) {
                is UpdateState.Available -> current.info.tagName
                is UpdateState.Downloaded -> current.info?.tagName
                else -> null
            }
            if (tagToSkip != null) {
                prefs.setSkippedUpdateVersion(tagToSkip)
            }
            _state.value = UpdateState.Idle
        } else if (current is UpdateState.Error || current is UpdateState.UpToDate) {
            _state.value = UpdateState.Idle
        }
    }

    override fun resetState() {
        _dialogVisible.value = false
        _bannerDismissed.value = false
        _state.value = UpdateState.Idle
    }

    private fun installApk(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { activity.startActivity(settingsIntent) }
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            activity.startActivity(installIntent)
        }.onFailure { error ->
            Timber.w(error, "Package installer unavailable, falling back to browser")
            if (lastReleasePageUrl.isNotBlank()) {
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, lastReleasePageUrl.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    private fun updatesDir(): File = File(context.cacheDir, "updates")

    private fun cachedApkFile(dir: File, info: UpdateInfo): File {
        val safeTag = info.tagName.replace(SAFE_TAG_REGEX, "_")
        return File(dir, "${safeTag}__${info.assetName}")
    }

    private fun findValidCachedApk(info: UpdateInfo): File? {
        val file = cachedApkFile(updatesDir(), info)
        return file.takeIf { isValidApkFile(it, info.assetSizeBytes) }
    }

    private fun isValidApkFile(file: File, expectedSizeBytes: Long): Boolean {
        if (!file.isFile || !file.canRead()) return false
        val size = file.length()
        if (size <= 0L) return false
        if (expectedSizeBytes > 0L && size != expectedSizeBytes) return false
        return true
    }

    private fun cleanupOldApks(dir: File, keepFileName: String? = null) {
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name == keepFileName) return@forEach
            if (file.name.endsWith(".apk") || file.name.endsWith(".part")) {
                file.delete()
            }
        }
    }

    companion object {
        private val SAFE_TAG_REGEX = Regex("[^a-zA-Z0-9._-]")
        private const val RELEASES_API_URL =
            "https://api.github.com/repos/pass-with-high-score/blockads-android/releases?per_page=20"
        private const val AUTO_CHECK_COOLDOWN_MS = 6L * 60 * 60 * 1000L
    }
}
