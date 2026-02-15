package app.pwhs.blockads.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProfileManager(
    private val profileDao: ProtectionProfileDao,
    private val filterListDao: FilterListDao,
    private val appPrefs: AppPreferences,
    private val filterRepo: FilterListRepository
) {

    companion object {
        private const val TAG = "ProfileManager"

        /** URLs for the Default profile: basic ads & trackers. */
        val DEFAULT_FILTER_URLS = setOf(
            "https://abpvn.com/android/abpvn.txt",
            "https://raw.githubusercontent.com/bigdargon/hostsVN/master/hosts",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        )

        /** URLs for the Strict profile: all ads, trackers, analytics. */
        val STRICT_FILTER_URLS = DEFAULT_FILTER_URLS + setOf(
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
            "https://filters.adtidy.org/extension/ublock/filters/2.txt",
            "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            "https://easylist.to/easylist/fanboy-annoyance.txt"
        )

        /** URLs for the Family profile: ads + adult content + gambling. */
        val FAMILY_FILTER_URLS = DEFAULT_FILTER_URLS + setOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts"
        )

        /** URLs for the Gaming profile: basic ads only. */
        val GAMING_FILTER_URLS = setOf(
            "https://abpvn.com/android/abpvn.txt",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        )
    }

    /**
     * Seed preset profiles if none exist.
     */
    suspend fun seedPresetsIfNeeded() = withContext(Dispatchers.IO) {
        val existing = profileDao.getAllSync()
        if (existing.isNotEmpty()) return@withContext

        val presets = listOf(
            ProtectionProfile(
                name = "Default",
                profileType = ProtectionProfile.TYPE_DEFAULT,
                enabledFilterUrls = DEFAULT_FILTER_URLS.joinToString(","),
                safeSearchEnabled = false,
                youtubeRestrictedMode = false,
                isActive = true
            ),
            ProtectionProfile(
                name = "Strict",
                profileType = ProtectionProfile.TYPE_STRICT,
                enabledFilterUrls = STRICT_FILTER_URLS.joinToString(","),
                safeSearchEnabled = false,
                youtubeRestrictedMode = false
            ),
            ProtectionProfile(
                name = "Family",
                profileType = ProtectionProfile.TYPE_FAMILY,
                enabledFilterUrls = FAMILY_FILTER_URLS.joinToString(","),
                safeSearchEnabled = true,
                youtubeRestrictedMode = true
            ),
            ProtectionProfile(
                name = "Gaming",
                profileType = ProtectionProfile.TYPE_GAMING,
                enabledFilterUrls = GAMING_FILTER_URLS.joinToString(","),
                safeSearchEnabled = false,
                youtubeRestrictedMode = false
            )
        )

        presets.forEach { profileDao.insert(it) }
        Log.d(TAG, "Seeded ${presets.size} preset profiles")
    }

    /**
     * Switch to a profile: update filter list enabled states, SafeSearch, YouTube Restricted Mode,
     * and reload filters.
     */
    suspend fun switchToProfile(profileId: Long) = withContext(Dispatchers.IO) {
        val profile = profileDao.getById(profileId) ?: return@withContext
        Log.d(TAG, "Switching to profile: ${profile.name} (${profile.profileType})")

        // Deactivate all and activate the chosen profile
        profileDao.deactivateAll()
        profileDao.activate(profileId)

        // Store active profile id in preferences
        appPrefs.setActiveProfileId(profileId)

        // Apply filter list configuration
        val profileUrls = profile.enabledFilterUrls
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val allFilters = filterListDao.getAllSync()
        for (filter in allFilters) {
            val shouldBeEnabled = filter.url in profileUrls
            if (filter.isEnabled != shouldBeEnabled) {
                filterListDao.setEnabled(filter.id, shouldBeEnabled)
            }
        }

        // Apply SafeSearch & YouTube Restricted Mode
        appPrefs.setSafeSearchEnabled(profile.safeSearchEnabled)
        appPrefs.setYoutubeRestrictedMode(profile.youtubeRestrictedMode)

        // Reload filters
        filterRepo.loadAllEnabledFilters()

        Log.d(TAG, "Switched to profile: ${profile.name}")
    }

    /**
     * Get the filter URLs for a profile type.
     */
    fun getFilterUrlsForType(type: String): Set<String> = when (type) {
        ProtectionProfile.TYPE_DEFAULT -> DEFAULT_FILTER_URLS
        ProtectionProfile.TYPE_STRICT -> STRICT_FILTER_URLS
        ProtectionProfile.TYPE_FAMILY -> FAMILY_FILTER_URLS
        ProtectionProfile.TYPE_GAMING -> GAMING_FILTER_URLS
        else -> emptySet()
    }
}
