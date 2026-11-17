package app.pwhs.blockads.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `matchesChannel filters stable beta and nightly accurately`() {
        val stable = GitHubRelease(tagName = "v7.3.0", prerelease = false)
        val beta = GitHubRelease(tagName = "beta-7.3.0-1", prerelease = true)
        val pre = GitHubRelease(tagName = "pre-7.3.0-1", prerelease = true)
        val nightly = GitHubRelease(tagName = "nightly-20261117", prerelease = true)

        assertTrue(VersionComparator.matchesChannel(stable, UpdateChannel.STABLE))
        assertFalse(VersionComparator.matchesChannel(beta, UpdateChannel.STABLE))
        assertFalse(VersionComparator.matchesChannel(nightly, UpdateChannel.STABLE))

        assertTrue(VersionComparator.matchesChannel(beta, UpdateChannel.BETA))
        assertTrue(VersionComparator.matchesChannel(pre, UpdateChannel.BETA))
        assertFalse(VersionComparator.matchesChannel(nightly, UpdateChannel.BETA))

        assertTrue(VersionComparator.matchesChannel(nightly, UpdateChannel.NIGHTLY))
        assertFalse(VersionComparator.matchesChannel(beta, UpdateChannel.NIGHTLY))
    }

    @Test
    fun `isNewerRelease compares stable and beta semver`() {
        val stable730 = GitHubRelease(tagName = "v7.3.0", prerelease = false)
        val beta730 = GitHubRelease(tagName = "beta-7.3.0-1", prerelease = true)
        val beta7302 = GitHubRelease(tagName = "beta-7.3.0-2", prerelease = true)

        assertTrue(VersionComparator.isNewerRelease(stable730, UpdateChannel.STABLE, "7.2.0", 0L))
        assertFalse(VersionComparator.isNewerRelease(stable730, UpdateChannel.STABLE, "7.3.0", 0L))
        assertTrue(VersionComparator.isNewerRelease(stable730, UpdateChannel.STABLE, "7.3.0-beta.1", 0L))
        assertTrue(VersionComparator.isNewerRelease(beta7302, UpdateChannel.BETA, "7.3.0-beta.1", 0L))
        assertFalse(VersionComparator.isNewerRelease(beta730, UpdateChannel.BETA, "7.3.0-beta.1", 0L))
    }

    @Test
    fun `isNewerRelease compares nightly by publishedAt vs buildTimeMs`() {
        val buildTime = VersionComparator.parseIso8601Millis("2026-11-17T00:00:00Z")
        val newerNightly = GitHubRelease(
            tagName = "nightly",
            prerelease = true,
            publishedAt = "2026-11-17T01:00:00Z",
        )
        val sameNightly = GitHubRelease(
            tagName = "nightly",
            prerelease = true,
            publishedAt = "2026-11-17T00:02:00Z",
        )

        assertTrue(VersionComparator.isNewerRelease(newerNightly, UpdateChannel.NIGHTLY, "7.2.0", buildTime))
        assertFalse(VersionComparator.isNewerRelease(sameNightly, UpdateChannel.NIGHTLY, "7.2.0", buildTime))
    }

    @Test
    fun `selectApkAsset picks matching ABI and falls back to universal`() {
        val assets = listOf(
            GitHubAsset("app-github-armeabi-v7a-release.apk", 100L, "https://example.com/v7a.apk"),
            GitHubAsset("app-github-arm64-v8a-release.apk", 120L, "https://example.com/arm64.apk"),
            GitHubAsset("app-github-universal-release.apk", 200L, "https://example.com/uni.apk"),
            GitHubAsset("blockadstv-github-arm64-v8a-release.apk", 110L, "https://example.com/tv.apk"),
        )

        val pickedArm64 = VersionComparator.selectApkAsset(assets, "app-", arrayOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("app-github-arm64-v8a-release.apk", pickedArm64?.name)

        val pickedUniversal = VersionComparator.selectApkAsset(assets, "app-", arrayOf("riscv64"))
        assertEquals("app-github-universal-release.apk", pickedUniversal?.name)

        val pickedTv = VersionComparator.selectApkAsset(assets, "blockadstv-", arrayOf("arm64-v8a"))
        assertEquals("blockadstv-github-arm64-v8a-release.apk", pickedTv?.name)
    }
}
