package app.pwhs.blockads.update

import android.content.Context
import android.os.Build
import app.pwhs.blockads.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Checks for app updates using the GitHub Releases API.
 * Detects the install source (GitHub, Play Store, F-Droid) to direct users
 * to the correct download location.
 */
class UpdateChecker(
    private val client: HttpClient
) {

    companion object {
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/pass-with-high-score/blockads-android/releases/latest"

        private const val GITHUB_RELEASE_URL =
            "https://github.com/pass-with-high-score/blockads-android/releases/latest"
        private const val PLAY_STORE_URL =
            "market://details?id=app.pwhs.blockads"
        private const val PLAY_STORE_WEB_URL =
            "https://play.google.com/store/apps/details?id=app.pwhs.blockads"
        private const val FDROID_URL =
            "https://f-droid.org/packages/app.pwhs.blockads/"

        private val json = Json { ignoreUnknownKeys = true }
    }

    enum class InstallSource {
        GITHUB, PLAY_STORE, FDROID
    }

    data class UpdateInfo(
        val latestVersion: String,
        val changelog: String,
        val storeUrl: String,
        val webUrl: String
    )

    /**
     * Detect where the app was installed from using PackageManager.
     */
    fun detectInstallSource(context: Context): InstallSource {
        return try {
            val installerPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }

            Timber.d("Installer package: $installerPackage")

            when (installerPackage) {
                "com.android.vending" -> InstallSource.PLAY_STORE
                "org.fdroid.fdroid", "org.fdroid.basic" -> InstallSource.FDROID
                else -> InstallSource.GITHUB
            }
        } catch (e: Exception) {
            Timber.d("Failed to detect install source: $e")
            InstallSource.GITHUB
        }
    }

    /**
     * Check if a newer version is available on GitHub.
     * Returns UpdateInfo if an update is available, null otherwise.
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? {
        return try {
            val response = client.get(GITHUB_API_URL).bodyAsText()
            val release = json.decodeFromString<GitHubRelease>(response)

            val latestVersion = release.tagName.removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME

            if (isNewerVersion(latestVersion, currentVersion)) {
                val source = detectInstallSource(context)
                val (storeUrl, webUrl) = getStoreUrls(source)

                UpdateInfo(
                    latestVersion = latestVersion,
                    changelog = release.body.orEmpty(),
                    storeUrl = storeUrl,
                    webUrl = webUrl
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.d("Update check failed: $e")
            null
        }
    }

    /**
     * Compare two semantic version strings (e.g., "4.3.0" vs "4.2.1").
     * Returns true if [latest] is newer than [current].
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Get store URL + web fallback URL based on install source.
     */
    private fun getStoreUrls(source: InstallSource): Pair<String, String> = when (source) {
        InstallSource.PLAY_STORE -> PLAY_STORE_URL to PLAY_STORE_WEB_URL
        InstallSource.FDROID -> FDROID_URL to FDROID_URL
        InstallSource.GITHUB -> GITHUB_RELEASE_URL to GITHUB_RELEASE_URL
    }

    @Serializable
    private data class GitHubRelease(
        @kotlinx.serialization.SerialName("tag_name")
        val tagName: String,
        val body: String? = null
    )
}
