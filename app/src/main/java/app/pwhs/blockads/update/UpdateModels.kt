package app.pwhs.blockads.update

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class UpdateChannel(val id: String) {
    STABLE("stable"),
    BETA("beta"),
    NIGHTLY("nightly");

    companion object {
        fun fromId(id: String?): UpdateChannel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STABLE

        fun fromVersionName(versionName: String): UpdateChannel {
            val lower = versionName.lowercase()
            return when {
                "nightly" in lower -> NIGHTLY
                "beta" in lower || "pre" in lower || "rc" in lower || "alpha" in lower -> BETA
                else -> STABLE
            }
        }
    }
}

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val changelog: String,
    val downloadUrl: String,
    val assetName: String,
    val assetSizeBytes: Long,
    val publishedAtMillis: Long,
    val isNightly: Boolean = false,
    val isImmediate: Boolean = false,
    val releasePageUrl: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState
    data class Downloaded(val filePath: String) : UpdateState
    data object PlayReadyToInstall : UpdateState
    data class Error(val message: String) : UpdateState
}

interface UpdatePreferencesStore {
    val updateChannel: Flow<String?>
    suspend fun getUpdateChannelSnapshot(): String?
    suspend fun setUpdateChannel(channel: String)
    suspend fun getLastUpdateCheckMs(): Long
    suspend fun setLastUpdateCheckMs(timestampMs: Long)
    suspend fun getSkippedUpdateVersion(): String
    suspend fun setSkippedUpdateVersion(version: String)
}

interface AppUpdateController {
    val state: StateFlow<UpdateState>
    val supportsInAppUpdate: Boolean
    val supportsChannelSelection: Boolean

    suspend fun checkForUpdate(channel: UpdateChannel, manual: Boolean)
    suspend fun startUpdate(activity: Activity)
    fun completeUpdate()
    suspend fun dismissUpdate(skipVersion: Boolean)
    fun resetState()
}
