package app.pwhs.blockads.data

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FilterListRepository(
    private val context: Context,
    private val filterListDao: FilterListDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val client: HttpClient
) {

    companion object {
        private const val CACHE_DIR = "filter_cache"
        const val BLOCK_REASON_CUSTOM_RULE = "CUSTOM_RULE"
        const val BLOCK_REASON_FILTER_LIST = "FILTER_LIST"
        const val BLOCK_REASON_SECURITY = "SECURITY"
        const val BLOCK_REASON_FIREWALL = "FIREWALL"

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
            // ── Security / Phishing / Malware ───────────────────────────
            FilterList(
                name = "URLhaus Malicious URL Blocklist",
                url = "https://urlhaus.abuse.ch/downloads/hostfile/",
                description = "Blocks malware distribution sites — updated frequently by abuse.ch",
                isEnabled = true,
                isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY
            ),
            FilterList(
                name = "PhishTank Blocklist",
                url = "https://phishing.army/download/phishing_army_blocklist.txt",
                description = "Blocks known phishing websites that steal personal information",
                isEnabled = true,
                isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY
            ),
            FilterList(
                name = "Malware Domain List",
                url = "https://raw.githubusercontent.com/RPiList/specials/master/Blocklisten/malware",
                description = "Community-curated list of domains distributing malware",
                isEnabled = true,
                isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY
            ),
        )
    }

    // Trie-based domain storage: mmap'd binary files for near-zero heap usage
    @Volatile
    private var adTrie: MmapDomainTrie? = null
    @Volatile
    private var securityTrie: MmapDomainTrie? = null

    private val whitelistedDomains = ConcurrentHashMap.newKeySet<String>()

    // Custom rules - higher priority than filter lists (small sets, keep as HashSet)
    private val customBlockDomains = ConcurrentHashMap.newKeySet<String>()
    private val customAllowDomains = ConcurrentHashMap.newKeySet<String>()

    private val trieDir get() = File(context.filesDir, "trie_cache")

    val domainCount: Int get() = adTrie?.size ?: 0

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
        // Priority 1: Check custom allow rules first (@@||example.com^)
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) {
            return false
        }

        // Priority 2: Check custom block rules (||example.com^)
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) {
            return true
        }

        // Priority 3: Check whitelist — whitelisted domains are always allowed
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) {
            return false
        }

        // Priority 4: Check security domains via Trie (malware/phishing)
        if (securityTrie?.containsOrParent(domain) == true) {
            return true
        }

        // Priority 5: Check ad domains via Trie (mmap'd, near-zero heap)
        return adTrie?.containsOrParent(domain) ?: false
    }

    /**
     * Returns a key identifying the reason a domain is blocked.
     * Returns empty string if the domain is not blocked.
     * Use BlockReason constants; resolve to localized strings in UI.
     */
    fun getBlockReason(domain: String): String {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) {
            return ""
        }
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) {
            return BLOCK_REASON_CUSTOM_RULE
        }
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) {
            return ""
        }
        if (securityTrie?.containsOrParent(domain) == true) {
            return BLOCK_REASON_SECURITY
        }
        if (adTrie?.containsOrParent(domain) == true) {
            return BLOCK_REASON_FILTER_LIST
        }
        return ""
    }

    suspend fun loadCustomRules() {
        val blockDomains = customDnsRuleDao.getBlockDomains()
        val allowDomains = customDnsRuleDao.getAllowDomains()

        customBlockDomains.clear()
        customBlockDomains.addAll(blockDomains.map { it.lowercase() })

        customAllowDomains.clear()
        customAllowDomains.addAll(allowDomains.map { it.lowercase() })

        Timber.e("Loaded ${customBlockDomains.size} custom block rules and ${customAllowDomains.size} custom allow rules")
    }

    suspend fun loadWhitelist() {
        val domains = whitelistDomainDao.getAllDomains()
        whitelistedDomains.clear()
        whitelistedDomains.addAll(domains.map { it.lowercase() })
        Timber.d("Loaded ${whitelistedDomains.size} whitelisted domains")
    }

    /**
     * Seeds default filter lists. Fetches all existing URLs in a single query,
     * then only inserts missing ones. Skips entirely if all defaults are present.
     */
    suspend fun seedDefaultsIfNeeded() {
        val existingUrls = filterListDao.getAllUrls().toHashSet()
        val toInsert = DEFAULT_LISTS.filter { it.url !in existingUrls }
        if (toInsert.isEmpty()) return

        for (filter in toInsert) {
            filterListDao.insert(filter)
            Timber.d("Seeded filter: ${filter.name}")
        }
    }

    /**
     * Load all enabled filter lists and merge into Tries.
     * Builds in-memory Trie → serializes to binary file → mmap for near-zero heap.
     * On subsequent startups, reuses existing binary if cache files haven't changed.
     */
    suspend fun loadAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val enabledLists = filterListDao.getEnabled()
            if (enabledLists.isEmpty()) {
                adTrie = null
                securityTrie = null
                return@withContext Result.success(0)
            }

            val startTime = System.currentTimeMillis()
            trieDir.mkdirs()
            val adTrieFile = File(trieDir, "ad_domains.trie")
            val secTrieFile = File(trieDir, "security_domains.trie")

            // ── Phase 1: Ad domains ──────────────────────────────────
            // Build, serialize, then RELEASE the in-memory trie before
            // starting security domains. This halves peak heap usage.
            val adFilters = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
            var adCount = 0
            if (adFilters.isNotEmpty()) {
                val adTrieBuilder = DomainTrie()
                for (filter in adFilters) {
                    try {
                        val sizeBefore = adTrieBuilder.size
                        loadSingleFilterToTrie(filter, adTrieBuilder)
                        val loaded = adTrieBuilder.size - sizeBefore
                        filterListDao.updateStats(
                            id = filter.id,
                            count = loaded,
                            timestamp = System.currentTimeMillis()
                        )
                        Timber.d("Loaded $loaded domains from ${filter.name}")
                    } catch (e: Exception) {
                        Timber.d("Failed to load filter: ${filter.name}: $e")
                    }
                }
                adCount = adTrieBuilder.size
                if (adCount > 0) {
                    adTrieBuilder.saveToBinary(adTrieFile)
                }
                adTrieBuilder.clear() // Release heap before phase 2
            }

            // ── Phase 2: Security domains ────────────────────────────
            val secFilters = enabledLists.filter { it.category == FilterList.CATEGORY_SECURITY }
            var secCount = 0
            if (secFilters.isNotEmpty()) {
                val secTrieBuilder = DomainTrie()
                for (filter in secFilters) {
                    try {
                        val sizeBefore = secTrieBuilder.size
                        loadSingleFilterToTrie(filter, secTrieBuilder)
                        val loaded = secTrieBuilder.size - sizeBefore
                        filterListDao.updateStats(
                            id = filter.id,
                            count = loaded,
                            timestamp = System.currentTimeMillis()
                        )
                        Timber.d("Loaded $loaded domains from ${filter.name} (security)")
                    } catch (e: Exception) {
                        Timber.d("Failed to load filter: ${filter.name}: $e")
                    }
                }
                secCount = secTrieBuilder.size
                if (secCount > 0) {
                    secTrieBuilder.saveToBinary(secTrieFile)
                }
                secTrieBuilder.clear()
            }

            // ── Phase 3: Mmap the binary files (near-zero heap) ──────
            adTrie = if (adCount > 0) DomainTrie.loadFromMmap(adTrieFile) else null
            securityTrie = if (secCount > 0) DomainTrie.loadFromMmap(secTrieFile) else null

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("Loaded $adCount ad + $secCount security domains in ${elapsed}ms (Trie + mmap)")

            Result.success(adCount + secCount)
        } catch (e: Exception) {
            Timber.d("Failed to load filters: $e")
            Result.failure(e)
        }
    }

    /**
     * Load a single filter list into a DomainTrie.
     * Uses cache-first strategy: reads from local file if available.
     */
    private suspend fun loadSingleFilterToTrie(filter: FilterList, trie: DomainTrie) {
        val cacheFile = getCacheFile(filter)

        // Cache-first: use cached file if it exists
        if (cacheFile.exists() && cacheFile.length() > 0) {
            cacheFile.bufferedReader().use { reader ->
                parseHostsFileToTrie(reader, trie)
            }
            return
        }

        // No cache — download from network and save to cache
        try {
            cacheFile.parentFile?.mkdirs()
            val channel = client.get(filter.url).bodyAsChannel()
            cacheFile.outputStream().buffered().use { out ->
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) out.write(buffer, 0, bytesRead)
                }
            }
            cacheFile.bufferedReader().use { reader ->
                parseHostsFileToTrie(reader, trie)
            }
        } catch (e: Exception) {
            Timber.d("Network download failed for ${filter.name}: $e")
        }
    }

    /**
     * Force update a single filter list from network.
     * Streams download directly to disk to avoid loading entire file into memory.
     */
    suspend fun updateSingleFilter(filter: FilterList): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile(filter)
            cacheFile.parentFile?.mkdirs()

            // Stream download to cache file
            val channel = client.get(filter.url).bodyAsChannel()
            cacheFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) output.write(buffer, 0, bytesRead)
                }
            }

            // Count domains for stats
            var count = 0
            cacheFile.bufferedReader().use { reader ->
                val trie = DomainTrie()
                parseHostsFileToTrie(reader, trie)
                count = trie.size
            }

            filterListDao.updateStats(
                id = filter.id,
                count = count,
                timestamp = System.currentTimeMillis()
            )

            // Reload all enabled filters to rebuild merged trie
            loadAllEnabledFilters()

            Result.success(count)
        } catch (e: Exception) {
            Timber.d("Failed to update ${filter.name}: $e")
            Result.failure(e)
        }
    }

    private fun getCacheFile(filter: FilterList): File {
        val safeName = filter.url.hashCode().toString()
        return File(context.filesDir, "$CACHE_DIR/$safeName.txt")
    }

    /** Parse hosts file lines and add domains directly to a Trie. */
    private fun parseHostsFileToTrie(reader: BufferedReader, trie: DomainTrie) {
        reader.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') }
            .forEach { line ->
                when {
                    line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") -> {
                        val domain = line.substringAfter(' ').trim()
                            .split("\\s+".toRegex()).firstOrNull()
                        if (!domain.isNullOrBlank() && domain != "localhost") {
                            trie.add(domain.lowercase())
                        }
                    }

                    line.startsWith("||") && line.endsWith("^") -> {
                        val domain = line.removePrefix("||").removeSuffix("^").trim()
                        if (domain.isNotBlank() && domain.contains('.')) {
                            trie.add(domain.lowercase())
                        }
                    }

                    line.contains('.') && !line.contains(' ') && !line.contains('/') -> {
                        trie.add(line.lowercase())
                    }
                }
            }
    }

    /**
     * Get a preview list of domains from a filter's cached file.
     * Returns up to [limit] domains parsed from the hosts file.
     */
    suspend fun getDomainPreview(filter: FilterList, limit: Int = 100): List<String> =
        withContext(Dispatchers.IO) {
            val cacheFile = getCacheFile(filter)
            if (!cacheFile.exists()) return@withContext emptyList()

            val domains = mutableListOf<String>()
            cacheFile.bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') }
                    .forEach { line ->
                        if (domains.size >= limit) return@forEach
                        val domain = when {
                            line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") -> {
                                line.substringAfter(' ').trim()
                                    .split("\\s+".toRegex()).firstOrNull()
                                    ?.takeIf { it.isNotBlank() && it != "localhost" }
                            }
                            line.startsWith("||") && line.endsWith("^") -> {
                                line.removePrefix("||").removeSuffix("^").trim()
                                    .takeIf { it.isNotBlank() && it.contains('.') }
                            }
                            line.contains('.') && !line.contains(' ') && !line.contains('/') -> {
                                line.lowercase()
                            }
                            else -> null
                        }
                        if (domain != null) domains.add(domain.lowercase())
                    }
            }
            domains
        }

    fun clearCache() {
        adTrie = null
        securityTrie = null
        trieDir.deleteRecursively()
        File(context.filesDir, CACHE_DIR).deleteRecursively()
    }

    /**
     * Validate that a URL points to a valid filter/hosts file.
     * Downloads a small sample (up to 16KB) and checks if it contains
     * enough valid filter lines.
     *
     * @return Result.success(true) if valid, Result.failure with an appropriate exception otherwise.
     */
    suspend fun validateFilterUrl(url: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val channel = client.get(url).bodyAsChannel()
            val buffer = ByteArray(16_384) // Read at most 16KB for validation
            var totalRead = 0
            val outputStream = java.io.ByteArrayOutputStream()

            while (!channel.isClosedForRead && totalRead < buffer.size) {
                val bytesRead = channel.readAvailable(buffer, totalRead, buffer.size - totalRead)
                if (bytesRead <= 0) break
                outputStream.write(buffer, totalRead, bytesRead)
                totalRead += bytesRead
            }

            val sample = outputStream.toString(Charsets.UTF_8.name())
            val lines = sample.lines()

            var validFilterLines = 0
            val minValidLines = 3

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('!')) continue

                val isValidLine = when {
                    trimmed.startsWith("0.0.0.0 ") || trimmed.startsWith("127.0.0.1 ") -> true
                    trimmed.startsWith("||") && trimmed.endsWith("^") -> true
                    trimmed.contains('.') && !trimmed.contains(' ') && !trimmed.contains('/') 
                        && !trimmed.contains('<') && !trimmed.contains('>') -> true
                    else -> false
                }

                if (isValidLine) {
                    validFilterLines++
                    if (validFilterLines >= minValidLines) {
                        return@withContext Result.success(true)
                    }
                }
            }

            Result.failure(IllegalArgumentException("Not a valid filter list"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

