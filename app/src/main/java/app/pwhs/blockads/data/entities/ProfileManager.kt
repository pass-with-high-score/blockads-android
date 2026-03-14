package app.pwhs.blockads.data.entities

import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.data.dao.ProtectionProfileDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class ProfileManager(
    private val profileDao: ProtectionProfileDao,
    private val filterListDao: FilterListDao,
    private val appPrefs: AppPreferences,
    private val filterRepo: FilterListRepository
) {

    companion object {

        /** URLs for the Default profile: basic ads & trackers. */
        val DEFAULT_FILTER_URLS = setOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt"
        )

        /** URLs for the Strict profile: all ads, trackers, analytics. */
        val STRICT_FILTER_URLS = DEFAULT_FILTER_URLS + setOf(
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext",
            "https://filters.adtidy.org/extension/ublock/filters/2.txt",
            "https://filters.adtidy.org/extension/ublock/filters/11.txt"
        )

        /** URLs for the Family profile: ads + adult content + gambling. */
        val FAMILY_FILTER_URLS = DEFAULT_FILTER_URLS + setOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts"
        )

        /** URLs for the Gaming profile: basic ads only. */
        val GAMING_FILTER_URLS = setOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://easylist.to/easylist/easylist.txt"
        )

        /** URLs for the Strict Family profile: maximum blocking (ads + trackers + adult + gambling). */
        val STRICT_FAMILY_FILTER_URLS = STRICT_FILTER_URLS + FAMILY_FILTER_URLS
    }

    /**
     * Seed preset profiles if none exist, and add any missing ones.
     */
    suspend fun seedPresetsIfNeeded() = withContext(Dispatchers.IO) {
        val existing = profileDao.getAllSync()
        val existingTypes = existing.map { it.profileType }.toSet()

        val presets = listOf(
            ProtectionProfile(
                name = "Default",
                profileType = ProtectionProfile.TYPE_DEFAULT,
                enabledFilterUrls = DEFAULT_FILTER_URLS.joinToString(","),
                safeSearchEnabled = false,
                youtubeRestrictedMode = false,
                isActive = existing.isEmpty()
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
            ),
            ProtectionProfile(
                name = "Strict Family",
                profileType = ProtectionProfile.TYPE_STRICT_FAMILY,
                enabledFilterUrls = STRICT_FAMILY_FILTER_URLS.joinToString(","),
                safeSearchEnabled = true,
                youtubeRestrictedMode = true
            )
        )

        val missingPresets = presets.filter { it.profileType !in existingTypes }
        if (missingPresets.isNotEmpty()) {
            missingPresets.forEach { profileDao.insert(it) }
            Timber.d("Seeded ${missingPresets.size} missing preset profiles")
        }

        // Sync existing presets to ensure obsolete filters are removed and new ones added
        val existingPresets = existing.filter { ProtectionProfile.isPreset(it.profileType) }
        for (existingProfile in existingPresets) {
            val expectedUrls = getFilterUrlsForType(existingProfile.profileType).joinToString(",")
            val hasObsoleteUrls = existingProfile.enabledFilterUrls != expectedUrls
            
            // Only update if the URLs don't match exactly (this covers both additions and removals to the base preset)
            if (hasObsoleteUrls) {
                profileDao.update(
                    existingProfile.copy(
                        enabledFilterUrls = expectedUrls
                    )
                )
                Timber.d("Updated URLs for existing preset profile: ${existingProfile.name}")
            }
        }

        // Ensure the Default profile is fully activated via the standard switch logic during initial seed
        if (existing.isEmpty()) {
            val allProfiles = profileDao.getAllSync()
            val defaultProfile = allProfiles.firstOrNull {
                it.profileType == ProtectionProfile.TYPE_DEFAULT
            }
            if (defaultProfile != null) {
                // Use switchToProfile so filters and preferences are consistent
                switchToProfile(defaultProfile.id)
            }
        }
    }

    /**
     * Switch to a profile: update filter list enabled states, SafeSearch, YouTube Restricted Mode,
     * and reload filters.
     */
    suspend fun switchToProfile(profileId: Long) = withContext(Dispatchers.IO) {
        val profile = profileDao.getById(profileId) ?: return@withContext
        Timber.d("Switching to profile: ${profile.name} (${profile.profileType})")

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

        Timber.d("Switched to profile: ${profile.name}")
    }

    /**
     * Get the filter URLs for a profile type.
     */
    fun getFilterUrlsForType(type: String): Set<String> = when (type) {
        ProtectionProfile.TYPE_DEFAULT -> DEFAULT_FILTER_URLS
        ProtectionProfile.TYPE_STRICT -> STRICT_FILTER_URLS
        ProtectionProfile.TYPE_FAMILY -> FAMILY_FILTER_URLS
        ProtectionProfile.TYPE_GAMING -> GAMING_FILTER_URLS
        ProtectionProfile.TYPE_STRICT_FAMILY -> STRICT_FAMILY_FILTER_URLS
        else -> emptySet()
    }
}
