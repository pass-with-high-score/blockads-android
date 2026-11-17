package app.pwhs.blockadstv.update

import android.app.Activity
import android.content.Context
import app.pwhs.blockadstv.BuildConfig
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.coroutines.resume

@Serializable
data class StoreRemoteUpdateConfig(
    val store_update_enabled: Boolean = true,
    val store_min_version_code: Int = 0,
    val store_force_immediate: Boolean = false,
)

class StoreTvUpdateController(
    context: Context,
    private val httpClient: HttpClient,
    private val prefs: UpdatePreferencesStore,
) : TvUpdateController {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(context)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    override val supportsInAppUpdate: Boolean = true
    override val supportsChannelSelection: Boolean = false

    private var cachedAppUpdateInfo: AppUpdateInfo? = null
    private var cachedUpdateType: Int = AppUpdateType.FLEXIBLE

    private val installListener = InstallStateUpdatedListener { installState ->
        when (installState.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                val total = installState.totalBytesToDownload().coerceAtLeast(1L)
                val downloaded = installState.bytesDownloaded()
                _state.value = UpdateState.Downloading(
                    progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                    downloadedBytes = downloaded,
                    totalBytes = total,
                )
            }
            InstallStatus.DOWNLOADED -> {
                _state.value = UpdateState.PlayReadyToInstall
            }
            InstallStatus.FAILED, InstallStatus.CANCELED -> {
                _state.value = UpdateState.Idle
            }
            else -> Unit
        }
    }

    init {
        appUpdateManager.registerListener(installListener)
    }

    override suspend fun checkForUpdate(channel: UpdateChannel, manual: Boolean) {
        if (!manual) {
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLastUpdateCheckMs()
            if (now - lastCheck < AUTO_CHECK_COOLDOWN_MS) return
        }

        _state.value = UpdateState.Checking
        val remoteConfig = fetchRemoteConfig()
        prefs.setLastUpdateCheckMs(System.currentTimeMillis())

        if (!remoteConfig.store_update_enabled) {
            _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
            return
        }

        val info = runCatching { awaitAppUpdateInfo() }.getOrElse { error ->
            Timber.w(error, "Play In-App Update check failed on TV")
            _state.value = if (manual) {
                UpdateState.Error(error.message ?: "Unable to check Play Store updates")
            } else {
                UpdateState.Idle
            }
            return
        }

        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            _state.value = UpdateState.PlayReadyToInstall
            return
        }

        val availability = info.updateAvailability()
        val forceImmediate = remoteConfig.store_force_immediate ||
            BuildConfig.VERSION_CODE < remoteConfig.store_min_version_code ||
            info.updatePriority() >= IMMEDIATE_PRIORITY_THRESHOLD

        val updateType = if (forceImmediate && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            AppUpdateType.IMMEDIATE
        } else {
            AppUpdateType.FLEXIBLE
        }

        val isUpdateAvailable = (availability == UpdateAvailability.UPDATE_AVAILABLE &&
            info.isUpdateTypeAllowed(updateType)) ||
            availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS

        if (!isUpdateAvailable) {
            _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
            return
        }

        val versionLabel = info.availableVersionCode().toString()
        if (!manual && !forceImmediate && prefs.getSkippedUpdateVersion() == versionLabel) {
            _state.value = UpdateState.Idle
            return
        }

        cachedAppUpdateInfo = info
        cachedUpdateType = updateType
        _state.value = UpdateState.Available(
            UpdateInfo(
                versionName = "v$versionLabel",
                tagName = versionLabel,
                changelog = "",
                downloadUrl = "",
                assetName = "",
                assetSizeBytes = info.totalBytesToDownload().coerceAtLeast(0L),
                publishedAtMillis = 0L,
                isImmediate = updateType == AppUpdateType.IMMEDIATE,
            )
        )
    }

    override suspend fun startUpdate(activity: Activity) {
        val info = cachedAppUpdateInfo ?: runCatching { awaitAppUpdateInfo() }.getOrNull() ?: return
        runCatching {
            val options = AppUpdateOptions.newBuilder(cachedUpdateType).build()
            appUpdateManager.startUpdateFlowForResult(info, activity, options, PLAY_UPDATE_REQUEST_CODE)
        }.onFailure { error ->
            Timber.e(error, "Failed to start Play In-App Update flow on TV")
            _state.value = UpdateState.Error(error.message ?: "Failed to start Play update")
        }
    }

    override fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }

    override suspend fun dismissUpdate(skipVersion: Boolean) {
        val current = _state.value
        if (skipVersion && current is UpdateState.Available && !current.info.isImmediate) {
            prefs.setSkippedUpdateVersion(current.info.tagName)
        }
        _state.value = UpdateState.Idle
    }

    override fun resetState() {
        _state.value = UpdateState.Idle
    }

    private suspend fun fetchRemoteConfig(): StoreRemoteUpdateConfig {
        return withTimeoutOrNull(REMOTE_CONFIG_TIMEOUT_MS) {
            runCatching {
                val response = httpClient.get(REMOTE_CONFIG_URL)
                if (response.status.isSuccess()) {
                    json.decodeFromString<StoreRemoteUpdateConfig>(response.bodyAsText())
                } else {
                    StoreRemoteUpdateConfig()
                }
            }.getOrElse { error ->
                Timber.w(error, "Failed to fetch store remote update config, using defaults")
                StoreRemoteUpdateConfig()
            }
        } ?: StoreRemoteUpdateConfig()
    }

    private suspend fun awaitAppUpdateInfo(): AppUpdateInfo = suspendCancellableCoroutine { cont ->
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info -> if (cont.isActive) cont.resume(info) }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
    }

    companion object {
        private const val REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/pass-with-high-score/blockads-android/main/update-config.json"
        private const val REMOTE_CONFIG_TIMEOUT_MS = 5_000L
        private const val AUTO_CHECK_COOLDOWN_MS = 6L * 60 * 60 * 1000L
        private const val IMMEDIATE_PRIORITY_THRESHOLD = 4
        const val PLAY_UPDATE_REQUEST_CODE = 7002
    }
}
