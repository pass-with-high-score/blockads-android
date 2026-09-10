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
     * Save the currently enabled filter URLs to the active profile in the database.
     */
    suspend fun saveActiveProfileFilterUrls() = withContext(Dispatchers.IO) {
        val activeProfile = profileDao.getActive() ?: return@withContext
        val enabledUrls = filterListDao.getEnabled()
            .map { it.url }
            .toSet()
        val urlsString = enabledUrls.joinToString(",")
        if (activeProfile.enabledFilterUrls != urlsString) {
            profileDao.update(activeProfile.copy(enabledFilterUrls = urlsString))
            Timber.d("Saved enabled filter URLs for active profile '${activeProfile.name}': ${enabledUrls.size} filters")
        }
    }

    /**
     * Switch to a profile: save current profile's filter list configuration,
     * update active profile in DB & preferences, apply target profile's filter list
     * enabled states, SafeSearch, YouTube Restricted Mode, and reload filters.
     */
    suspend fun switchToProfile(profileId: Long) = withContext(Dispatchers.IO) {
        val targetProfile = profileDao.getById(profileId) ?: return@withContext
        Timber.d("Switching to profile: ${targetProfile.name} (${targetProfile.profileType})")

        // 1. Save currently enabled filters to the outgoing active profile before switching
        val currentActive = profileDao.getActive()
        if (currentActive != null && currentActive.id != profileId) {
            val enabledUrls = filterListDao.getEnabled()
                .map { it.url }
                .toSet()
            profileDao.update(currentActive.copy(enabledFilterUrls = enabledUrls.joinToString(",")))
            Timber.d("Saved ${enabledUrls.size} filter URLs to outgoing profile: ${currentActive.name}")
        }

        // 2. Deactivate all and activate target profile
        profileDao.deactivateAll()
        profileDao.activate(profileId)

        // 3. Store active profile id in preferences
        appPrefs.setActiveProfileId(profileId)

        // 4. Apply target profile's filter list configuration
        val targetUrls = if (targetProfile.enabledFilterUrls.isBlank() && ProtectionProfile.isPreset(targetProfile.profileType)) {
            getFilterUrlsForType(targetProfile.profileType)
        } else {
            targetProfile.enabledFilterUrls
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        val allFilters = filterListDao.getAllSync()
        for (filter in allFilters) {
            val shouldBeEnabled = filter.url in targetUrls
            if (filter.isEnabled != shouldBeEnabled) {
                filterListDao.setEnabled(filter.id, shouldBeEnabled)
            }
        }

        // 5. Apply SafeSearch & YouTube Restricted Mode
        appPrefs.setSafeSearchEnabled(targetProfile.safeSearchEnabled)
        appPrefs.setYoutubeRestrictedMode(targetProfile.youtubeRestrictedMode)

        // 6. Reload filters
        filterRepo.loadAllEnabledFilters()

        Timber.d("Switched to profile: ${targetProfile.name}")
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
