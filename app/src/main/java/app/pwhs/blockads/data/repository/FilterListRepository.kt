package app.pwhs.blockads.data.repository

import android.content.Context
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.entities.FilterList
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        const val BLOCK_REASON_UPSTREAM_DNS = "upstream_dns"

        val DEFAULT_LISTS = listOf(
            FilterList(
                name = "ABPVN",
                url = "https://abpvn.com/android/abpvn.txt",
                description = "Vietnamese ad filter list",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "HostsVN",
                url = "https://raw.githubusercontent.com/bigdargon/hostsVN/master/hosts",
                description = "Vietnamese hosts-based ad blocker",
                isEnabled = false,
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
                isEnabled = true,
                isBuiltIn = true
            ),
            FilterList(
                name = "EasyPrivacy",
                url = "https://easylist.to/easylist/easyprivacy.txt",
                description = "Blocks tracking scripts and privacy-invasive trackers",
                isEnabled = true,
                isBuiltIn = true
            ),
            FilterList(
                name = "Peter Lowe's Ad and tracking server list",
                url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext",
                description = "Lightweight host-based ad and tracking server blocklist",
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
                name = "AdGuard Social Media",
                url = "https://filters.adtidy.org/extension/ublock/filters/4.txt",
                description = "Blocks social media widgets — like buttons, share buttons, and embeds",
                isEnabled = false,
                isBuiltIn = true
            ),
            // ── Hagezi DNS Blocklists ────────────────────────────────────
            FilterList(
                name = "Hagezi Light",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/light.txt",
                description = "Hagezi Light — basic ad & tracker blocking with minimal false positives",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "Hagezi Normal",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/multi.txt",
                description = "Hagezi Normal — all-round protection against ads, tracking & malware",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "Hagezi Pro",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt",
                description = "Hagezi Pro — extended protection, recommended for advanced users",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "Hagezi Pro++",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.plus.txt",
                description = "Hagezi Pro++ — aggressive blocking, may break some apps/sites",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "Hagezi Ultimate",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt",
                description = "Hagezi Ultimate — extremely aggressive blocking, will break most apps/sites",
                isEnabled = false,
                isBuiltIn = true
            ),
            FilterList(
                name = "Hagezi TIF",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/tif.txt",
                description = "Hagezi Threat Intelligence — blocks malware, phishing, scam & cryptojacking",
                isEnabled = false,
                isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY
            ),
            // ── Security / Phishing / Malware ───────────────────────────
            FilterList(
                name = "URLhaus Malicious URL Blocklist",
                url = "https://urlhaus.abuse.ch/downloads/hostfile/",
                description = "Blocks malware distribution sites — updated frequently by abuse.ch",
                isEnabled = false,
                isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY
            ),
            FilterList(
                name = "PhishTank Blocklist",
                url = "https://phishing.army/download/phishing_army_blocklist.txt",
                description = "Blocks known phishing websites that steal personal information",
                isEnabled = false,
                isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY
            ),
            FilterList(
                name = "Malware Domain List",
                url = "https://raw.githubusercontent.com/RPiList/specials/master/Blocklisten/malware",
                description = "Community-curated list of domains distributing malware",
                isEnabled = false,
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

    private val loadMutex = Mutex()

    init {
        trieDir.mkdirs()
    }

    private val whitelistedDomains = ConcurrentHashMap.newKeySet<String>()

    // Custom rules - higher priority than filter lists (small sets, keep as HashSet)
    private val customBlockDomains = ConcurrentHashMap.newKeySet<String>()
    private val customAllowDomains = ConcurrentHashMap.newKeySet<String>()

    private val trieDir get() = File(context.filesDir, "trie_cache")

    private val _domainCountFlow = MutableStateFlow(0)
    val domainCountFlow: StateFlow<Int> = _domainCountFlow.asStateFlow()

    val domainCount: Int get() = _domainCountFlow.value

    fun getAdTriePath(): String? {
        val file = File(trieDir, "ad_domains.trie")
        return if (file.exists()) file.absolutePath else null
    }

    fun getSecurityTriePath(): String? {
        val file = File(trieDir, "security_domains.trie")
        return if (file.exists()) file.absolutePath else null
    }

    fun getAdBloomPath(): String? {
        val file = File(trieDir, "ad_domains.bloom")
        return if (file.exists()) file.absolutePath else null
    }

    fun getSecurityBloomPath(): String? {
        val file = File(trieDir, "security_domains.bloom")
        return if (file.exists()) file.absolutePath else null
    }

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
            // Check wildcard: *.remaining (e.g., *.example.com matches sub.example.com)
            if (checker("*.$d")) return true
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
        try {
            if (securityTrie?.containsOrParent(domain) == true) {
                return true
            }
        } catch (e: Exception) {
            Timber.e(e, "Security trie lookup failed for: $domain")
        }

        // Priority 5: Check ad domains via Trie (mmap'd, near-zero heap)
        return try {
            adTrie?.containsOrParent(domain) ?: false
        } catch (e: Exception) {
            Timber.e(e, "Ad trie lookup failed for: $domain")
            false
        }
    }

    /**
     * Checks if a domain has a custom rule override.
     * Used by the native Go engine to bypass the Binary Trie fast-path.
     * @return 1L for BLOCK override, 0L for ALLOW override, -1L for NO override.
     */
    fun hasCustomRule(domain: String): Long {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) {
            return 0L
        }
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) {
            return 0L
        }
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) {
            return 1L
        }
        return -1L
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
        try {
            if (securityTrie?.containsOrParent(domain) == true) {
                return BLOCK_REASON_SECURITY
            }
        } catch (_: Exception) {
        }
        try {
            if (adTrie?.containsOrParent(domain) == true) {
                return BLOCK_REASON_FILTER_LIST
            }
        } catch (_: Exception) {
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
     * Seeds default filter lists. Updates existing ones and adds new ones.
     * Also removes any built-in filters that are no longer in the DEFAULT_LISTS.
     */
    suspend fun seedDefaultsIfNeeded() {
        val existingLists = filterListDao.getAllSync()
        val existingByUrl = existingLists.associateBy { it.url }
        val existingByName = existingLists.associateBy { it.name }

        // Find which default lists need to be inserted or updated
        for (defaultList in DEFAULT_LISTS) {
            val existing = existingByUrl[defaultList.url] ?: existingByName[defaultList.name]
            if (existing == null) {
                // Insert new built-in list
                filterListDao.insert(defaultList)
                Timber.d("Seeded new built-in filter: ${defaultList.name}")
            } else if (existing.name != defaultList.name ||
                existing.url != defaultList.url ||
                existing.description != defaultList.description || 
                existing.category != defaultList.category || 
                !existing.isBuiltIn) {
                // Update existing built-in list to match new default configuration
                // Keep the isEnabled, domainCount, lastUpdated state from the existing list
                filterListDao.update(
                    existing.copy(
                        name = defaultList.name,
                        url = defaultList.url,
                        description = defaultList.description,
                        category = defaultList.category,
                        isBuiltIn = true
                    )
                )
                Timber.d("Updated built-in filter info: ${defaultList.name}")
            }
        }

        // Remove old built-in lists that are no longer in DEFAULT_LISTS
        val defaultNames = DEFAULT_LISTS.map { it.name }.toSet()
        val obsoleteBuiltIn = existingLists.filter { it.isBuiltIn && it.name !in defaultNames }
        for (obsolete in obsoleteBuiltIn) {
            filterListDao.delete(obsolete)
            Timber.d("Removed obsolete built-in filter: ${obsolete.name}")
        }
    }

    /**
     * Load all enabled filter lists and merge into Tries.
     *
     * Three loading strategies:
     * 1. **Cache HIT** — fingerprint unchanged → mmap existing binary (~50ms)
     * 2. **Incremental ADD** — only new filters enabled, none removed/changed
     *    → load existing Trie from binary, parse only new filters, re-serialize
     * 3. **Full REBUILD** — filters removed or changed → rebuild everything
     */
    suspend fun loadAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            try {

                val enabledLists = filterListDao.getEnabled()
                if (enabledLists.isEmpty()) {
                    adTrie = null
                    securityTrie = null
                    File(trieDir, "ad_domains.trie").delete()
                    File(trieDir, "security_domains.trie").delete()
                    File(trieDir, "ad_domains.bloom").delete()
                    File(trieDir, "security_domains.bloom").delete()
                    File(trieDir, "trie_fingerprint.txt").delete()
                    _domainCountFlow.value = 0
                    return@withContext Result.success(0)
                }

                val startTime = System.currentTimeMillis()
                trieDir.mkdirs()
                val adTrieFile = File(trieDir, "ad_domains.trie")
                val secTrieFile = File(trieDir, "security_domains.trie")
                val fingerprintFile = File(trieDir, "trie_fingerprint.txt")

                // ── Build per-filter fingerprints ──
                val currentFpMap = buildFingerprintMap(enabledLists)
                val currentFingerprint = currentFpMap.entries
                    .sortedBy { it.key }
                    .joinToString(";") { "${it.key}:${it.value}" }

                val savedFingerprint = try {
                    if (fingerprintFile.exists()) fingerprintFile.readText() else ""
                } catch (_: Exception) {
                    ""
                }

                // ── Strategy 1: Cache HIT — nothing changed ──
                if (currentFingerprint == savedFingerprint
                    && adTrieFile.exists() && adTrieFile.length() > 0
                ) {
                    adTrie = DomainTrie.loadFromMmap(adTrieFile)
                    securityTrie = if (secTrieFile.exists() && secTrieFile.length() > 0) {
                        DomainTrie.loadFromMmap(secTrieFile)
                    } else null

                    val elapsed = System.currentTimeMillis() - startTime
                    val totalCount = (adTrie?.size ?: 0) + (securityTrie?.size ?: 0)
                    _domainCountFlow.value = totalCount
                    Timber.d("Trie cache HIT — loaded $totalCount domains via mmap in ${elapsed}ms")
                    return@withContext Result.success(totalCount)
                }

                // ── Determine what changed ──
                val savedFpMap = parseFingerprintMap(savedFingerprint)
                val currentIds = currentFpMap.keys
                val savedIds = savedFpMap.keys

                val addedFilterIds = currentIds - savedIds
                val removedFilterIds = savedIds - currentIds
                val changedFilterIds = currentIds.intersect(savedIds)
                    .filter { currentFpMap[it] != savedFpMap[it] }
                    .toSet()

                val isAddOnly = removedFilterIds.isEmpty() && changedFilterIds.isEmpty()
                        && addedFilterIds.isNotEmpty()
                        && adTrieFile.exists() && adTrieFile.length() > 0

                if (isAddOnly) {
                    // ── Strategy 2: Incremental ADD — only new filters ──
                    Timber.d("Trie INCREMENTAL — ${addedFilterIds.size} new filter(s), loading existing + adding new")

                    val addedFilters = enabledLists.filter { it.id in addedFilterIds }

                    val adCount = incrementalAdd(
                        addedFilters.filter { it.category != FilterList.CATEGORY_SECURITY },
                        adTrieFile
                    )
                    val secCount = incrementalAdd(
                        addedFilters.filter { it.category == FilterList.CATEGORY_SECURITY },
                        secTrieFile
                    )

                    adTrie = if (adTrieFile.exists() && adTrieFile.length() > 0) {
                        DomainTrie.loadFromMmap(adTrieFile)
                    } else null
                    securityTrie = if (secTrieFile.exists() && secTrieFile.length() > 0) {
                        DomainTrie.loadFromMmap(secTrieFile)
                    } else null

                    saveFingerprintAndLog(
                        fingerprintFile,
                        currentFingerprint,
                        startTime,
                        "INCREMENTAL"
                    )
                    val totalCount = (adTrie?.size ?: 0) + (securityTrie?.size ?: 0)
                    _domainCountFlow.value = totalCount

                    return@withContext Result.success(totalCount)
                }

                // ── Strategy 3: Full REBUILD ──
                Timber.d("Trie FULL REBUILD — removed=${removedFilterIds.size}, changed=${changedFilterIds.size}, added=${addedFilterIds.size}")

                val adFilters =
                    enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
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
                        val tempFile = File(adTrieFile.parent, adTrieFile.name + ".tmp")
                        adTrieBuilder.saveToBinary(tempFile)
                        tempFile.renameTo(adTrieFile)
                        // Generate bloom filter for fast pre-filtering in Go
                        val adBloomFile = File(trieDir, "ad_domains.bloom")
                        adTrieBuilder.saveBloomFilter(adBloomFile)
                    }
                    adTrieBuilder.clear()
                } else {
                    adTrieFile.delete()
                    File(trieDir, "ad_domains.bloom").delete()
                }

                val secFilters =
                    enabledLists.filter { it.category == FilterList.CATEGORY_SECURITY }
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
                        val tempFile = File(secTrieFile.parent, secTrieFile.name + ".tmp")
                        secTrieBuilder.saveToBinary(tempFile)
                        tempFile.renameTo(secTrieFile)
                        // Generate bloom filter for fast pre-filtering in Go
                        val secBloomFile = File(trieDir, "security_domains.bloom")
                        secTrieBuilder.saveBloomFilter(secBloomFile)
                    }
                    secTrieBuilder.clear()
                } else {
                    secTrieFile.delete()
                    File(trieDir, "security_domains.bloom").delete()
                }

                adTrie = if (adCount > 0) DomainTrie.loadFromMmap(adTrieFile) else null
                securityTrie =
                    if (secCount > 0) DomainTrie.loadFromMmap(secTrieFile) else null

                saveFingerprintAndLog(
                    fingerprintFile,
                    currentFingerprint,
                    startTime,
                    "FULL REBUILD"
                )
                val finalCount = adCount + secCount
                _domainCountFlow.value = finalCount
                Result.success(finalCount)
            } catch (e: Exception) {
                Timber.d("Failed to load filters: $e")
                Result.failure(e)
            }
        }
    }

    /**
     * Incremental add: load existing Trie from binary, parse only new filters, re-serialize.
     * Returns the total domain count in the updated Trie.
     */
    private suspend fun incrementalAdd(
        newFilters: List<FilterList>,
        trieFile: File
    ): Int {
        if (newFilters.isEmpty()) return 0

        // Load existing Trie from binary (if any)
        val trieBuilder = if (trieFile.exists() && trieFile.length() > 0) {
            DomainTrie.loadFromBinary(trieFile) ?: DomainTrie()
        } else {
            DomainTrie()
        }

        val existingCount = trieBuilder.size
        Timber.d("Incremental: loaded $existingCount existing domains from binary")

        // Parse only the NEW filter files
        for (filter in newFilters) {
            try {
                val sizeBefore = trieBuilder.size
                loadSingleFilterToTrie(filter, trieBuilder)
                val loaded = trieBuilder.size - sizeBefore
                filterListDao.updateStats(
                    id = filter.id,
                    count = loaded,
                    timestamp = System.currentTimeMillis()
                )
                Timber.d("Incremental: added $loaded domains from ${filter.name}")
            } catch (e: Exception) {
                Timber.d("Failed to load filter: ${filter.name}: $e")
            }
        }

        // Re-serialize trie + regenerate bloom filter
        if (trieBuilder.size > 0) {
            val tempFile = File(trieFile.parent, trieFile.name + ".tmp")
            trieBuilder.saveToBinary(tempFile)
            tempFile.renameTo(trieFile)
            // Regenerate bloom filter for the updated trie
            val bloomFile = File(trieFile.parent, trieFile.nameWithoutExtension + ".bloom")
            trieBuilder.saveBloomFilter(bloomFile)
        }
        val totalCount = trieBuilder.size
        trieBuilder.clear()
        return totalCount
    }

    /**
     * Build a map of filter ID → cache file lastModified timestamp.
     */
    private fun buildFingerprintMap(enabledLists: List<FilterList>): Map<Long, Long> {
        return enabledLists.associate { filter ->
            val cacheFile = getCacheFile(filter)
            val lastMod = if (cacheFile.exists()) cacheFile.lastModified() else 0L
            filter.id to lastMod
        }
    }

    /**
     * Parse a saved fingerprint string back into a map.
     */
    private fun parseFingerprintMap(fingerprint: String): Map<Long, Long> {
        if (fingerprint.isBlank()) return emptyMap()
        return fingerprint.split(";")
            .filter { it.contains(":") }
            .associate {
                val (id, ts) = it.split(":", limit = 2)
                id.toLongOrNull()?.let { idL -> idL to (ts.toLongOrNull() ?: 0L) }
                    ?: (0L to 0L)
            }
            .filterKeys { it != 0L }
    }

    private fun saveFingerprintAndLog(
        fingerprintFile: File,
        fingerprint: String,
        startTime: Long,
        strategy: String
    ) {
        try {
            fingerprintFile.writeText(fingerprint)
        } catch (e: Exception) {
            Timber.d("Failed to save trie fingerprint: $e")
        }
        val elapsed = System.currentTimeMillis() - startTime
        val totalCount = (adTrie?.size ?: 0) + (securityTrie?.size ?: 0)
        Timber.d("$strategy — $totalCount domains loaded in ${elapsed}ms")
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
            .filter {
                it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') && !it.startsWith(
                    "@@"
                )
            }
            .forEach { line ->
                when {
                    // Hosts format: "0.0.0.0<whitespace>domain" or "127.0.0.1<whitespace>domain"
                    // Use regex split to handle both spaces and tabs (e.g. URLhaus uses tabs)
                    line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") -> {
                        val parts = line.split("\\s+".toRegex())
                        val domain = parts.getOrNull(1)?.trim()
                        if (!domain.isNullOrBlank() && domain != "localhost") {
                            trie.add(domain.lowercase())
                        }
                    }

                    line.startsWith("||") -> {
                        var domain = line.removePrefix("||").trim()
                        if (domain.endsWith("^")) {
                            domain = domain.removeSuffix("^")
                        }
                        if (domain.isNotBlank()) {
                            trie.add(domain.lowercase())
                        }
                    }

                    !line.contains(' ') && !line.contains('\t') && !line.contains('/') -> {
                        // Allow wildcards (e.g., *.ads.example.com) and regular/short domains
                        var domain = line.lowercase()
                        if (domain.endsWith("^")) {
                            domain = domain.removeSuffix("^")
                        }

                        if (domain.isNotBlank() && domain.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == '*' }) {
                            trie.add(domain)
                        }
                    }
                }
            }
    }

    /**
     * Finds which specific filter lists a domain (or its parent domain) is currently blocked by.
     * This scans the cached files of all currently enabled filter lists.
     * 
     * @param targetDomain The domain that was blocked (e.g. "ads.example.com")
     * @return List of filter list names that contain this domain (e.g. ["EasyList", "AdGuard DNS"])
     */
    suspend fun findBlockingFilterLists(targetDomain: String): List<String> = withContext(Dispatchers.IO) {
        val enabledLists = filterListDao.getEnabled()
        if (enabledLists.isEmpty()) return@withContext emptyList()

        val matchedListNames = mutableListOf<String>()
        val domainParts = targetDomain.lowercase().split(".")
        
        // Generate domain variations to check (exact, parent, wildcard)
        // For "sub.ads.example.com": 
        // -> sub.ads.example.com
        // -> ads.example.com, *.ads.example.com
        // -> example.com, *.example.com
        val domainVariations = mutableSetOf(targetDomain.lowercase())
        for (i in 1 until domainParts.size - 1) { // Stop before TLD
            val parentDomain = domainParts.subList(i, domainParts.size).joinToString(".")
            domainVariations.add(parentDomain)
            domainVariations.add("*.$parentDomain")
        }

        // Iterate through all enabled lists
        for (filter in enabledLists) {
            val cacheFile = getCacheFile(filter)
            if (!cacheFile.exists() || cacheFile.length() == 0L) continue

            try {
                // Read line by line to keep memory footprint low
                var matched = false
                cacheFile.bufferedReader().use { reader ->
                    for (line in reader.lineSequence()) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('!')) continue

                        val parsedDomain = parseSingleLineDomain(trimmed)
                        if (parsedDomain != null && domainVariations.contains(parsedDomain)) {
                            matched = true
                            break // Found a match, no need to read the rest of this file
                        }
                    }
                }
                if (matched) {
                    matchedListNames.add(filter.name)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error scanning cache file for ${filter.name}")
            }
        }
        
        return@withContext matchedListNames
    }

    /** Helper to extract domain from a hosts/adblock-formatted line */
    private fun parseSingleLineDomain(line: String): String? {
        return when {
            line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") -> {
                line.split("\\s+".toRegex()).getOrNull(1)?.trim()?.lowercase()
            }
            line.startsWith("||") -> {
                line.removePrefix("||").substringBefore('^').trim().lowercase()
            }
            !line.contains(' ') && !line.contains('\t') && !line.contains('/') -> {
                line.substringBefore('^').trim().lowercase()
            }
            else -> null
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
                            line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") -> {
                                line.split("\\s+".toRegex()).getOrNull(1)?.trim()
                                    ?.takeIf { it.isNotBlank() && it != "localhost" }
                            }

                            line.startsWith("||") && line.endsWith("^") -> {
                                line.removePrefix("||").removeSuffix("^").trim()
                                    .takeIf { it.isNotBlank() && it.contains('.') }
                            }

                            line.contains('.') && !line.contains(' ') && !line.contains('\t') && !line.contains('/') -> {
                                line.lowercase()
                            }

                            else -> null
                        }
                        if (domain != null) domains.add(domain.lowercase())
                    }
            }
            domains
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
            val outputStream = ByteArrayOutputStream()

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
