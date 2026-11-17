package app.pwhs.blockadstv.update

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal object VersionComparator {

    private val SEMVER_REGEX = Regex("""(\d+)\.(\d+)(?:\.(\d+))?(?:[._-]?(alpha|beta|pre|rc)[._-]?(\d+)?|[._-](\d+))?""", RegexOption.IGNORE_CASE)
    private val DATE_REGEX = Regex("""(\d{8})""")
    private const val NIGHTLY_BUILD_SKEW_MS = 5 * 60 * 1000L

    fun matchesChannel(release: GitHubRelease, channel: UpdateChannel): Boolean {
        if (release.draft) return false
        val tag = release.tagName.lowercase()
        return when (channel) {
            UpdateChannel.STABLE -> !release.prerelease && tag.startsWith("v")
            UpdateChannel.BETA -> tag.startsWith("beta-") || tag.startsWith("pre-") ||
                (release.prerelease && !tag.startsWith("nightly"))
            UpdateChannel.NIGHTLY -> tag.startsWith("nightly")
        }
    }

    fun isNewerRelease(
        release: GitHubRelease,
        channel: UpdateChannel,
        currentVersionName: String,
        currentBuildTimeMs: Long,
    ): Boolean {
        if (channel == UpdateChannel.NIGHTLY) {
            val publishedMs = parseIso8601Millis(release.publishedAt)
            if (currentBuildTimeMs > 0L && publishedMs > 0L) {
                return publishedMs > currentBuildTimeMs + NIGHTLY_BUILD_SKEW_MS
            }
            val releaseDate = DATE_REGEX.find(release.tagName)?.groupValues?.get(1)?.toIntOrNull()
            val currentDate = DATE_REGEX.find(currentVersionName)?.groupValues?.get(1)?.toIntOrNull()
            if (releaseDate != null && currentDate != null) {
                return releaseDate > currentDate
            }
            return release.tagName != currentVersionName
        }

        val remoteVer = parseVersion(release.tagName) ?: return false
        val localVer = parseVersion(currentVersionName) ?: return true
        return compareVersions(remoteVer, localVer) > 0
    }

    fun selectApkAsset(
        assets: List<GitHubAsset>,
        modulePrefix: String,
        supportedAbis: Array<String>,
    ): GitHubAsset? {
        val apkAssets = assets.filter { asset ->
            val lower = asset.name.lowercase()
            lower.endsWith(".apk") &&
                lower.startsWith(modulePrefix.lowercase()) &&
                !lower.contains("-debug") &&
                !lower.contains("-unsigned")
        }
        if (apkAssets.isEmpty()) return null

        for (abi in supportedAbis) {
            val match = apkAssets.firstOrNull { it.name.contains("-$abi-", ignoreCase = true) || it.name.contains("-$abi.", ignoreCase = true) }
            if (match != null) return match
        }
        return apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apkAssets.firstOrNull()
    }

    fun parseIso8601Millis(iso: String): Long {
        if (iso.isBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        )
        for (pattern in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(iso)?.time ?: 0L
            }
        }
        return 0L
    }

    private data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRank: Int,
        val preNumber: Int,
    )

    private fun parseVersion(raw: String): ParsedVersion? {
        val trimmed = raw.trim()
        val hasBetaPrefix = trimmed.startsWith("beta-", ignoreCase = true) ||
            trimmed.startsWith("pre-", ignoreCase = true)
        val cleaned = trimmed
            .removePrefix("v")
            .removePrefix("V")
            .removePrefix("beta-")
            .removePrefix("pre-")
        val match = SEMVER_REGEX.find(cleaned) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        val preLabel = match.groupValues[4].lowercase()
        val preNum = match.groupValues[5].toIntOrNull()
            ?: match.groupValues[6].toIntOrNull()
            ?: 0
        val preRank = when {
            preLabel == "alpha" -> 1
            preLabel == "beta" || preLabel == "pre" -> 2
            preLabel == "rc" -> 3
            hasBetaPrefix -> 2
            else -> 4
        }
        return ParsedVersion(major, minor, patch, preRank, preNum)
    }

    private fun compareVersions(a: ParsedVersion, b: ParsedVersion): Int {
        if (a.major != b.major) return a.major.compareTo(b.major)
        if (a.minor != b.minor) return a.minor.compareTo(b.minor)
        if (a.patch != b.patch) return a.patch.compareTo(b.patch)
        if (a.preRank != b.preRank) return a.preRank.compareTo(b.preRank)
        return a.preNumber.compareTo(b.preNumber)
    }
}
