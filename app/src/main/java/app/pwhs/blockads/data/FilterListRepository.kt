package app.pwhs.blockads.data

import android.content.Context
import android.util.Log
import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class FilterListRepository(
    private val context: Context,
    private val filterListDao: FilterListDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val client: HttpClient
) {

    companion object {
        private const val TAG = "FilterListRepo"
        private const val CACHE_DIR = "filter_cache"

        val DEFAULT_LISTS = listOf(
            FilterList(
                name = "ABPVN",
                url = "https://abpvn.com/android/abpvn.txt",
                description = "Vietnamese ad filter list",
                isEnabled = true,
                isBuiltIn = true
            ),
            FilterList(
                name = "HostsVN",
                url = "https://raw.githubusercontent.com/bigdargon/hostsVN/master/hosts",
                description = "Vietnamese hosts-based ad blocker",
                isEnabled = true,
                isBuiltIn = true
            ),
            FilterList(
                name = "AdGuard DNS",
                url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                description = "AdGuard DNS filter for ad & tracker blocking",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "StevenBlack Unified",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                description = "Unified hosts from multiple curated sources — ads & malware",
                isEnabled = true,
                isBuiltIn = true
            ),
            FilterList(
                name = "StevenBlack Fakenews",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews-only/hosts",
                description = "Block fake news domains",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "StevenBlack Gambling",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts",
                description = "Block gambling & betting sites",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "StevenBlack Adult",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
                description = "Block adult content domains",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "StevenBlack Social",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts",
                description = "Block social media platforms",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "EasyList",
                url = "https://easylist.to/easylist/easylist.txt",
                description = "Most popular global ad filter — blocks ads on most websites",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "EasyPrivacy",
                url = "https://easylist.to/easylist/easyprivacy.txt",
                description = "Blocks tracking scripts and privacy-invasive trackers",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "Peter Lowe's Ad and tracking server list",
                url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
                description = "Lightweight host-based ad and tracking server blocklist",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "uBlock filters",
                url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
                description = "uBlock Origin filters — blocks pop-ups, anti-adblock, and annoyances",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "AdGuard Base Filter",
                url = "https://filters.adtidy.org/extension/ublock/filters/2.txt",
                description = "AdGuard base ad filter — comprehensive alternative to EasyList",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "AdGuard Mobile Ads",
                url = "https://filters.adtidy.org/extension/ublock/filters/11.txt",
                description = "Optimized filter for mobile ads in apps and mobile websites",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "Fanboy's Annoyances",
                url = "https://easylist.to/easylist/fanboy-annoyance.txt",
                description = "Blocks cookie banners, pop-ups, newsletter prompts, and chat boxes",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "uBlock filters – Annoyances",
                url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances.txt",
                description = "Blocks social media ads and suggestions on Facebook, YouTube, Twitter, etc.",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "AdGuard Social Media",
                url = "https://filters.adtidy.org/extension/ublock/filters/4.txt",
                description = "Blocks social media widgets — like buttons, share buttons, and embeds",
                isEnabled = false,
                isBuiltIn = true
            ),
        )
    }

    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedDomains = ConcurrentHashMap.newKeySet<String>()

    // Bloom filter for fast negative lookups (reduces exact match checks)
    @Volatile
    private var blockedDomainsBloomFilter: BloomFilter<CharSequence>? = null

    // Expected false positive rate of 1% for Bloom filter
    private val bloomFilterFpp = 0.01

    val domainCount: Int get() = blockedDomains.size

    /**
     * Check if a domain or any of its parent domains matches a condition.
     *
     * This helper function iterates through a domain and all its parent domains
     * (by removing the leftmost subdomain each time), checking each against the
     * provided checker function.
     *
     * Example: For "sub.example.com", checks:
     * 1. "sub.example.com"
     * 2. "example.com"
     * 3. "com"
     *
     * @param domain The domain to check (e.g., "ads.example.com")
     * @param checker A function that returns true if the domain matches the condition.
     *                This could check a Set, Bloom filter, or any other data structure.
     * @return true if the domain or any parent domain matches; false otherwise
     *
     * Usage examples:
     * ```kotlin
     * // Check whitelist (Set)
     * checkDomainAndParents(domain) { whitelistedDomains.contains(it) }
     *
     * // Check Bloom filter
     * checkDomainAndParents(domain) { bloomFilter.mightContain(it) }
     *
     * // Check exact blocklist (HashMap)
     * checkDomainAndParents(domain) { blockedDomains.contains(it) }
     * ```
     */
    private inline fun checkDomainAndParents(
        domain: String,
        checker: (String) -> Boolean
    ): Boolean {
        if (checker(domain)) return true
        var d = domain
        while (d.contains('.')) {
            d = d.substringAfter('.')
            if (checker(d)) return true
        }
        return false
    }

    fun isBlocked(domain: String): Boolean {
        // Check whitelist first — whitelisted domains are always allowed
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) {
            return false
        }

        // Use Bloom filter for fast negative check
        // If Bloom filter says "definitely not present", skip exact lookup
        val bloomFilter = blockedDomainsBloomFilter
        if (bloomFilter != null) {
            // Check if domain or any parent might be in blocklist
            val possiblyBlocked = checkDomainAndParents(domain) { bloomFilter.mightContain(it) }
            // If no match in Bloom filter, definitely not blocked
            if (!possiblyBlocked) return false
        }

        // Check exact blocklist (only if Bloom filter suggested possibility)
        return checkDomainAndParents(domain) { blockedDomains.contains(it) }
    }

    suspend fun loadWhitelist() {
        val domains = whitelistDomainDao.getAllDomains()
        whitelistedDomains.clear()
        whitelistedDomains.addAll(domains.map { it.lowercase() })
        Log.d(TAG, "Loaded ${whitelistedDomains.size} whitelisted domains")
    }

    /**
     * Seeds default filter lists. Uses URL-based dedup so new built-in
     * filters are added on app updates without creating duplicates.
     */
    suspend fun seedDefaultsIfNeeded() {
        for (filter in DEFAULT_LISTS) {
            if (filterListDao.getByUrl(filter.url) == null) {
                filterListDao.insert(filter)
                Log.d(TAG, "Seeded filter: ${filter.name}")
            }
        }
    }

    /**
     * Load all enabled filter lists and merge into a single blocklist.
     */
    suspend fun loadAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val enabledLists = filterListDao.getEnabled()
            if (enabledLists.isEmpty()) {
                blockedDomains.clear()
                blockedDomainsBloomFilter = null
                return@withContext Result.success(0)
            }

            val newDomains = ConcurrentHashMap.newKeySet<String>()
            var totalLoaded = 0

            for (filter in enabledLists) {
                try {
                    val domains = loadSingleFilter(filter)
                    newDomains.addAll(domains)
                    totalLoaded += domains.size

                    filterListDao.updateStats(
                        id = filter.id,
                        count = domains.size,
                        timestamp = System.currentTimeMillis()
                    )
                    Log.d(TAG, "Loaded ${domains.size} domains from ${filter.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load filter: ${filter.name}", e)
                }
            }

            blockedDomains.clear()
            blockedDomains.addAll(newDomains)

            // Build Bloom filter for fast negative lookups
            buildBloomFilter(newDomains)

            Log.d(TAG, "Total unique domains loaded: ${blockedDomains.size}")

            Result.success(blockedDomains.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load filters", e)
            Result.failure(e)
        }
    }

    /**
     * Build a Bloom filter from the domain set for memory-efficient lookups.
     */
    private fun buildBloomFilter(domains: Set<String>) {
        if (domains.isEmpty()) {
            blockedDomainsBloomFilter = null
            return
        }

        try {
            val startTime = System.currentTimeMillis()
            // Create Bloom filter with expected insertions and FPP
            val filter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                domains.size,
                bloomFilterFpp
            )

            // Add all domains to Bloom filter
            domains.forEach { filter.put(it) }

            blockedDomainsBloomFilter = filter
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Built Bloom filter for ${domains.size} domains in ${elapsed}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build Bloom filter, falling back to direct lookup", e)
            blockedDomainsBloomFilter = null
        }
    }

    /**
     * Load a single filter list, using cache with fallback to network.
     */
    private suspend fun loadSingleFilter(filter: FilterList): Set<String> {
        val cacheFile = getCacheFile(filter)
        val domains = mutableSetOf<String>()

        // Try network first
        try {
            val body = client.get(filter.url).bodyAsText()
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(body)
            parseHostsFile(body, domains)
            return domains
        } catch (e: Exception) {
            Log.w(TAG, "Network failed for ${filter.name}, trying cache", e)
        }

        // Fallback to cache
        if (cacheFile.exists()) {
            parseHostsFile(cacheFile.readText(), domains)
        }

        return domains
    }

    /**
     * Force update a single filter list from network.
     */
    suspend fun updateSingleFilter(filter: FilterList): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val body = client.get(filter.url).bodyAsText()

            val cacheFile = getCacheFile(filter)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(body)

            val domains = mutableSetOf<String>()
            parseHostsFile(body, domains)

            filterListDao.updateStats(
                id = filter.id,
                count = domains.size,
                timestamp = System.currentTimeMillis()
            )

            // Reload all enabled filters to rebuild merged blocklist
            loadAllEnabledFilters()

            Result.success(domains.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update ${filter.name}", e)
            Result.failure(e)
        }
    }

    private fun getCacheFile(filter: FilterList): File {
        val safeName = filter.url.hashCode().toString()
        return File(context.filesDir, "$CACHE_DIR/$safeName.txt")
    }

    private fun parseHostsFile(content: String, output: MutableSet<String>) {
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') }
            .forEach { line ->
                when {
                    line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") -> {
                        val domain = line.substringAfter(' ').trim()
                            .split("\\s+".toRegex()).firstOrNull()
                        if (!domain.isNullOrBlank() && domain != "localhost") {
                            output.add(domain.lowercase())
                        }
                    }

                    line.startsWith("||") && line.endsWith("^") -> {
                        val domain = line.removePrefix("||").removeSuffix("^").trim()
                        if (domain.isNotBlank() && domain.contains('.')) {
                            output.add(domain.lowercase())
                        }
                    }

                    line.contains('.') && !line.contains(' ') && !line.contains('/') -> {
                        output.add(line.lowercase())
                    }
                }
            }
    }

    fun clearCache() {
        blockedDomains.clear()
        blockedDomainsBloomFilter = null
        File(context.filesDir, CACHE_DIR).deleteRecursively()
    }
}
