package app.pwhs.blockads.data.repository

import android.content.Context
import app.pwhs.blockads.data.dao.CustomDnsRuleDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.dao.WhitelistDomainDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.remote.FilterDownloadManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
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
    private val client: HttpClient,
    private val downloadManager: FilterDownloadManager
) {

    companion object {
        private const val CACHE_DIR = "filter_cache"
        const val BLOCK_REASON_CUSTOM_RULE = "CUSTOM_RULE"
        const val BLOCK_REASON_FILTER_LIST = "FILTER_LIST"
        const val BLOCK_REASON_SECURITY = "SECURITY"
        const val BLOCK_REASON_FIREWALL = "FIREWALL"
        const val BLOCK_REASON_UPSTREAM_DNS = "upstream_dns"

        private const val FILTER_LIST_JSON_URL =
            "https://raw.githubusercontent.com/pass-with-high-score/blockads_filter_bin/refs/heads/main/output/filter_lists.json"
        private const val BASE_BIN_URL =
            "https://raw.githubusercontent.com/pass-with-high-score/blockads_filter_bin/refs/heads/main/output"

        val DEFAULT_LISTS = listOf(
            // ── Vietnamese ──────────────────────────────────────────────
            FilterList(
                name = "ABPVN",
                url = "https://abpvn.com/android/abpvn.txt",
                description = "Vietnamese ad filter list",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 18307,
                bloomUrl = "$BASE_BIN_URL/abpvn.bloom",
                trieUrl = "$BASE_BIN_URL/abpvn.trie",
                originalUrl = "https://abpvn.com/android/abpvn.txt"
            ),
            FilterList(
                name = "HostsVN",
                url = "https://raw.githubusercontent.com/bigdargon/hostsVN/master/hosts",
                description = "Vietnamese hosts-based ad blocker",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 17583,
                bloomUrl = "$BASE_BIN_URL/hostsvn.bloom",
                trieUrl = "$BASE_BIN_URL/hostsvn.trie",
                originalUrl = "https://raw.githubusercontent.com/bigdargon/hostsVN/master/hosts"
            ),
            // ── Popular Global Filters ──────────────────────────────────
            FilterList(
                name = "StevenBlack Unified",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                description = "Unified hosts from multiple curated sources — ads & malware",
                isEnabled = true, isBuiltIn = true,
                ruleCount = 81919,
                bloomUrl = "$BASE_BIN_URL/stevenblack.bloom",
                trieUrl = "$BASE_BIN_URL/stevenblack.trie",
                originalUrl = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
            ),
            FilterList(
                name = "StevenBlack Gambling",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts",
                description = "Block gambling & betting sites",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 5936,
                bloomUrl = "$BASE_BIN_URL/stevenblack_gambling.bloom",
                trieUrl = "$BASE_BIN_URL/stevenblack_gambling.trie",
                originalUrl = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts"
            ),
            FilterList(
                name = "StevenBlack Adult",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
                description = "Block adult content domains",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 76509,
                bloomUrl = "$BASE_BIN_URL/stevenblack_porn.bloom",
                trieUrl = "$BASE_BIN_URL/stevenblack_porn.trie",
                originalUrl = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
            ),
            FilterList(
                name = "StevenBlack Social",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts",
                description = "Block social media platforms",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 3243,
                bloomUrl = "$BASE_BIN_URL/stevenblack_social.bloom",
                trieUrl = "$BASE_BIN_URL/stevenblack_social.trie",
                originalUrl = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts"
            ),
            FilterList(
                name = "EasyList",
                url = "https://easylist.to/easylist/easylist.txt",
                description = "Most popular global ad filter — blocks ads on most websites",
                isEnabled = true, isBuiltIn = true,
                ruleCount = 70207,
                bloomUrl = "$BASE_BIN_URL/easylist.bloom",
                trieUrl = "$BASE_BIN_URL/easylist.trie",
                cssUrl = "$BASE_BIN_URL/easylist.css",
                originalUrl = "https://easylist.to/easylist/easylist.txt"
            ),
            FilterList(
                name = "EasyPrivacy",
                url = "https://easylist.to/easylist/easyprivacy.txt",
                description = "Blocks tracking scripts and privacy-invasive trackers",
                isEnabled = true, isBuiltIn = true,
                ruleCount = 50933,
                bloomUrl = "$BASE_BIN_URL/easyprivacy.bloom",
                trieUrl = "$BASE_BIN_URL/easyprivacy.trie",
                cssUrl = "$BASE_BIN_URL/easyprivacy.css",
                originalUrl = "https://easylist.to/easylist/easyprivacy.txt"
            ),
            FilterList(
                name = "Peter Lowe's Ad and tracking server list",
                url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext",
                description = "Lightweight host-based ad and tracking server blocklist",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 3516,
                bloomUrl = "$BASE_BIN_URL/yoyo_adservers.bloom",
                trieUrl = "$BASE_BIN_URL/yoyo_adservers.trie",
                originalUrl = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"
            ),
            // ── AdGuard ──────────────────────────────────────────────────
            FilterList(
                name = "AdGuard DNS",
                url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                description = "AdGuard DNS filter for ad & tracker blocking",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 162083,
                bloomUrl = "$BASE_BIN_URL/adguard_dns.bloom",
                trieUrl = "$BASE_BIN_URL/adguard_dns.trie",
                originalUrl = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
            ),
            FilterList(
                name = "AdGuard Base Filter",
                url = "https://filters.adtidy.org/extension/ublock/filters/2.txt",
                description = "AdGuard base ad filter — comprehensive alternative to EasyList",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 96398,
                bloomUrl = "$BASE_BIN_URL/adguard_base.bloom",
                trieUrl = "$BASE_BIN_URL/adguard_base.trie",
                cssUrl = "$BASE_BIN_URL/adguard_base.css",
                originalUrl = "https://filters.adtidy.org/extension/ublock/filters/2.txt"
            ),
            FilterList(
                name = "AdGuard Mobile Ads",
                url = "https://filters.adtidy.org/extension/ublock/filters/11.txt",
                description = "Optimized filter for mobile ads in apps and mobile websites",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 4620,
                bloomUrl = "$BASE_BIN_URL/adguard_mobile.bloom",
                trieUrl = "$BASE_BIN_URL/adguard_mobile.trie",
                cssUrl = "$BASE_BIN_URL/adguard_mobile.css",
                originalUrl = "https://filters.adtidy.org/extension/ublock/filters/11.txt"
            ),
            FilterList(
                name = "AdGuard Social Media",
                url = "https://filters.adtidy.org/extension/ublock/filters/4.txt",
                description = "Blocks social media widgets — like buttons, share buttons, and embeds",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 10020,
                bloomUrl = "$BASE_BIN_URL/adguard_social.bloom",
                trieUrl = "$BASE_BIN_URL/adguard_social.trie",
                cssUrl = "$BASE_BIN_URL/adguard_social.css",
                originalUrl = "https://filters.adtidy.org/extension/ublock/filters/4.txt"
            ),
            // ── Hagezi DNS Blocklists ────────────────────────────────────
            FilterList(
                name = "Hagezi Light",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/light.txt",
                description = "Hagezi Light — basic ad & tracker blocking with minimal false positives",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 69283,
                bloomUrl = "$BASE_BIN_URL/hagezi_light.bloom",
                trieUrl = "$BASE_BIN_URL/hagezi_light.trie",
                originalUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/light.txt"
            ),
            FilterList(
                name = "Hagezi Normal",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/multi.txt",
                description = "Hagezi Normal — all-round protection against ads, tracking & malware",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 145970,
                bloomUrl = "$BASE_BIN_URL/hagezi_multi.bloom",
                trieUrl = "$BASE_BIN_URL/hagezi_multi.trie",
                originalUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/multi.txt"
            ),
            FilterList(
                name = "Hagezi Pro",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt",
                description = "Hagezi Pro — extended protection, recommended for advanced users",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 188957,
                bloomUrl = "$BASE_BIN_URL/hagezi_pro.bloom",
                trieUrl = "$BASE_BIN_URL/hagezi_pro.trie",
                originalUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt"
            ),
            FilterList(
                name = "Hagezi Pro++",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.plus.txt",
                description = "Hagezi Pro++ — aggressive blocking, may break some apps/sites",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 236213,
                bloomUrl = "$BASE_BIN_URL/hagezi_pro_plus.bloom",
                trieUrl = "$BASE_BIN_URL/hagezi_pro_plus.trie",
                originalUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.plus.txt"
            ),
            FilterList(
                name = "Hagezi Ultimate",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt",
                description = "Hagezi Ultimate — extremely aggressive blocking, will break most apps/sites",
                isEnabled = false, isBuiltIn = true,
                ruleCount = 279444,
                bloomUrl = "$BASE_BIN_URL/hagezi_ultimate.bloom",
                trieUrl = "$BASE_BIN_URL/hagezi_ultimate.trie",
                originalUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt"
            ),
            FilterList(
                name = "Hagezi TIF",
                url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/tif.txt",
                description = "Hagezi Threat Intelligence — blocks malware, phishing, scam & cryptojacking",
                isEnabled = false, isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY,
                ruleCount = 688511,
                bloomUrl = "$BASE_BIN_URL/hagezi_tif.bloom",
                trieUrl = "$BASE_BIN_URL/hagezi_tif.trie",
                originalUrl = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/tif.txt"
            ),
            // ── Security / Phishing / Malware ───────────────────────────
            FilterList(
                name = "URLhaus Malicious URL Blocklist",
                url = "https://urlhaus.abuse.ch/downloads/hostfile/",
                description = "Blocks malware distribution sites — updated frequently by abuse.ch",
                isEnabled = false, isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY,
                ruleCount = 470,
                bloomUrl = "$BASE_BIN_URL/urlhaus_malware.bloom",
                trieUrl = "$BASE_BIN_URL/urlhaus_malware.trie",
                originalUrl = "https://urlhaus.abuse.ch/downloads/hostfile/"
            ),
            FilterList(
                name = "PhishTank Blocklist",
                url = "https://phishing.army/download/phishing_army_blocklist.txt",
                description = "Blocks known phishing websites that steal personal information",
                isEnabled = false, isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY,
                ruleCount = 153524,
                bloomUrl = "$BASE_BIN_URL/phishing_army.bloom",
                trieUrl = "$BASE_BIN_URL/phishing_army.trie",
                originalUrl = "https://phishing.army/download/phishing_army_blocklist.txt"
            ),
            FilterList(
                name = "Malware Domain List",
                url = "https://raw.githubusercontent.com/RPiList/specials/master/Blocklisten/malware",
                description = "Community-curated list of domains distributing malware",
                isEnabled = false, isBuiltIn = true,
                category = FilterList.CATEGORY_SECURITY,
                ruleCount = 477891,
                bloomUrl = "$BASE_BIN_URL/rpilist_malware.bloom",
                trieUrl = "$BASE_BIN_URL/rpilist_malware.trie",
                originalUrl = "https://raw.githubusercontent.com/RPiList/specials/master/Blocklisten/malware"
            ),
        )
    }

    // Paths to pre-compiled binary files for Go Native Engine (CSV strings)
    @Volatile private var adTriePaths: String = ""
    @Volatile private var securityTriePaths: String = ""
    @Volatile private var adBloomPaths: String = ""
    @Volatile private var securityBloomPaths: String = ""

    private val loadMutex = Mutex()

    init {
        File(context.filesDir, "remote_filters").mkdirs()
    }

    private val whitelistedDomains = ConcurrentHashMap.newKeySet<String>()
    private val customBlockDomains = ConcurrentHashMap.newKeySet<String>()
    private val customAllowDomains = ConcurrentHashMap.newKeySet<String>()

    private val _domainCountFlow = MutableStateFlow(0)
    val domainCountFlow: StateFlow<Int> = _domainCountFlow.asStateFlow()
    val domainCount: Int get() = _domainCountFlow.value

    fun getAdTriePath(): String = adTriePaths
    fun getSecurityTriePath(): String = securityTriePaths
    fun getAdBloomPath(): String = adBloomPaths
    fun getSecurityBloomPath(): String = securityBloomPaths

    fun getCosmeticCssPath(): String? {
        val file = File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    private inline fun checkDomainAndParents(domain: String, checker: (String) -> Boolean): Boolean {
        if (checker(domain)) return true
        var d = domain
        while (d.contains('.')) {
            d = d.substringAfter('.')
            if (checker(d)) return true
            if (checker("*.$d")) return true
        }
        return false
    }

    fun isBlocked(domain: String): Boolean {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) return false
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) return true
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) return false
        return false
    }

    fun hasCustomRule(domain: String): Long {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) return 0L
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) return 0L
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) return 1L
        return -1L
    }

    fun getBlockReason(domain: String): String {
        if (checkDomainAndParents(domain) { customAllowDomains.contains(it) }) return ""
        if (checkDomainAndParents(domain) { customBlockDomains.contains(it) }) return BLOCK_REASON_CUSTOM_RULE
        if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) return ""
        return ""
    }

    suspend fun loadCustomRules() {
        val blockDomains = customDnsRuleDao.getBlockDomains()
        val allowDomains = customDnsRuleDao.getAllowDomains()
        customBlockDomains.clear()
        customBlockDomains.addAll(blockDomains.map { it.lowercase() })
        customAllowDomains.clear()
        customAllowDomains.addAll(allowDomains.map { it.lowercase() })
        Timber.d("Loaded ${customBlockDomains.size} block + ${customAllowDomains.size} allow custom rules")
    }

    suspend fun loadWhitelist() {
        val domains = whitelistDomainDao.getAllDomains()
        whitelistedDomains.clear()
        whitelistedDomains.addAll(domains.map { it.lowercase() })
        Timber.d("Loaded ${whitelistedDomains.size} whitelisted domains")
    }

    // ────────────────────────────────────────────────────────────────────
    // Seeding & Remote Sync
    // ────────────────────────────────────────────────────────────────────

    /**
     * Seeds default filter lists with pre-compiled URLs.
     * Updates existing entries so bloomUrl/trieUrl/cssUrl/ruleCount stay current.
     */
    suspend fun seedDefaultsIfNeeded() {
        val existingLists = filterListDao.getAllSync()
        val existingByName = existingLists.associateBy { it.name }

        for (defaultList in DEFAULT_LISTS) {
            val existing = existingByName[defaultList.name]
            if (existing == null) {
                filterListDao.insert(defaultList.copy(domainCount = defaultList.ruleCount))
                Timber.d("Seeded new built-in filter: ${defaultList.name}")
            } else {
                val needsUpdate = existing.url != defaultList.url ||
                    existing.description != defaultList.description ||
                    existing.category != defaultList.category ||
                    existing.bloomUrl != defaultList.bloomUrl ||
                    existing.trieUrl != defaultList.trieUrl ||
                    existing.cssUrl != defaultList.cssUrl ||
                    existing.ruleCount != defaultList.ruleCount ||
                    existing.domainCount != defaultList.ruleCount ||
                    existing.originalUrl != defaultList.originalUrl ||
                    !existing.isBuiltIn

                if (needsUpdate) {
                    filterListDao.update(
                        existing.copy(
                            url = defaultList.url,
                            description = defaultList.description,
                            category = defaultList.category,
                            bloomUrl = defaultList.bloomUrl,
                            trieUrl = defaultList.trieUrl,
                            cssUrl = defaultList.cssUrl,
                            ruleCount = defaultList.ruleCount,
                            domainCount = defaultList.ruleCount,
                            originalUrl = defaultList.originalUrl,
                            isBuiltIn = true
                        )
                    )
                    Timber.d("Updated built-in filter: ${defaultList.name}")
                }
            }
        }

        val defaultNames = DEFAULT_LISTS.map { it.name }.toSet()
        val obsolete = existingLists.filter { it.isBuiltIn && it.name !in defaultNames }
        for (o in obsolete) {
            filterListDao.delete(o)
            Timber.d("Removed obsolete built-in filter: ${o.name}")
        }
    }

    /**
     * Fetches the remote filter_lists.json and syncs pre-compiled URLs to the local DB.
     * Keeps bloomUrl/trieUrl/cssUrl/ruleCount fresh when the server regenerates binaries.
     */
    suspend fun fetchAndSyncRemoteFilterLists() = withContext(Dispatchers.IO) {
        try {
            val channel = client.get(FILTER_LIST_JSON_URL).bodyAsChannel()
            val buffer = ByteArray(256 * 1024)
            val output = java.io.ByteArrayOutputStream()
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read > 0) output.write(buffer, 0, read)
            }
            val jsonString = output.toString(Charsets.UTF_8.name())
            val remoteLists = parseRemoteFilterJson(jsonString)
            if (remoteLists.isEmpty()) return@withContext

            val existingLists = filterListDao.getAllSync()
            val existingByName = existingLists.associateBy { it.name }

            for (remote in remoteLists) {
                val existing = existingByName[remote.name]
                if (existing != null) {
                    if (existing.bloomUrl != remote.bloomUrl ||
                        existing.trieUrl != remote.trieUrl ||
                        existing.cssUrl != (remote.cssUrl ?: "") ||
                        existing.ruleCount != remote.ruleCount
                    ) {
                        filterListDao.update(
                            existing.copy(
                                bloomUrl = remote.bloomUrl,
                                trieUrl = remote.trieUrl,
                                domainCount = remote.ruleCount,
                                cssUrl = remote.cssUrl ?: "",
                                ruleCount = remote.ruleCount,
                                originalUrl = remote.originalUrl ?: existing.originalUrl
                            )
                        )
                        Timber.d("Synced URLs for: ${remote.name}")
                    }
                } else {
                    val category = if (remote.category == "security") FilterList.CATEGORY_SECURITY else FilterList.CATEGORY_AD
                    filterListDao.insert(
                        FilterList(
                            name = remote.name,
                            url = remote.originalUrl ?: "",
                            description = remote.description ?: "",
                            isEnabled = remote.isEnabled,
                            isBuiltIn = remote.isBuiltIn,
                            category = category,
                            ruleCount = remote.ruleCount,
                            bloomUrl = remote.bloomUrl,
                            trieUrl = remote.trieUrl,
                            cssUrl = remote.cssUrl ?: "",
                            originalUrl = remote.originalUrl ?: ""
                        )
                    )
                    Timber.d("Inserted new remote filter: ${remote.name}")
                }
            }
            Timber.d("Synced ${remoteLists.size} filters from remote JSON")
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch remote filter list JSON")
        }
    }

    /**
     * Simple JSON parser for the filter_lists.json array.
     */
    private fun parseRemoteFilterJson(json: String): List<app.pwhs.blockads.data.remote.models.FilterList> {
        return try {
            val results = mutableListOf<app.pwhs.blockads.data.remote.models.FilterList>()
            val objects = json.split("},").map {
                it.trim().removePrefix("[").removeSuffix("]").trim() + "}"
            }

            for (obj in objects) {
                val cleaned = obj.trim().removePrefix("{").removeSuffix("}").removeSuffix("},")
                if (cleaned.isBlank()) continue

                fun extractString(key: String): String? {
                    val pattern = "\"$key\"\\s*:\\s*\"(.*?)\"".toRegex()
                    return pattern.find(cleaned)?.groupValues?.get(1)
                        ?.replace("\\u0026", "&")
                }
                fun extractInt(key: String): Int {
                    val pattern = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
                    return pattern.find(cleaned)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                fun extractBoolean(key: String): Boolean {
                    val pattern = "\"$key\"\\s*:\\s*(true|false)".toRegex()
                    return pattern.find(cleaned)?.groupValues?.get(1) == "true"
                }

                val name = extractString("name") ?: continue
                val bloomUrl = extractString("bloomUrl") ?: continue
                val trieUrl = extractString("trieUrl") ?: continue

                results.add(
                    app.pwhs.blockads.data.remote.models.FilterList(
                        name = name,
                        id = extractString("id") ?: name.lowercase().replace(" ", "_"),
                        description = extractString("description"),
                        isEnabled = extractBoolean("isEnabled"),
                        isBuiltIn = extractBoolean("isBuiltIn"),
                        category = extractString("category"),
                        ruleCount = extractInt("ruleCount"),
                        bloomUrl = bloomUrl,
                        trieUrl = trieUrl,
                        cssUrl = extractString("cssUrl"),
                        originalUrl = extractString("originalUrl")
                    )
                )
            }
            results
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse remote filter JSON")
            emptyList()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Filter Loading & Download
    // ────────────────────────────────────────────────────────────────────

    /**
     * Load all enabled filter lists using FilterDownloadManager.
     * Downloads pre-compiled .bloom/.trie files and collects paths as CSV strings for the Go engine.
     */
    suspend fun loadAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            try {
                val enabledLists = filterListDao.getEnabled()
                if (enabledLists.isEmpty()) {
                    adTriePaths = ""
                    securityTriePaths = ""
                    adBloomPaths = ""
                    securityBloomPaths = ""
                    File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css").delete()
                    _domainCountFlow.value = 0
                    return@withContext Result.success(0)
                }

                val startTime = System.currentTimeMillis()
                val adTrieSb = StringBuilder()
                val secTrieSb = StringBuilder()
                val adBloomSb = StringBuilder()
                val secBloomSb = StringBuilder()
                var totalCount = 0

                for (filter in enabledLists) {
                    if (filter.bloomUrl.isNullOrEmpty() || filter.trieUrl.isNullOrEmpty()) {
                        Timber.d("Skipping ${filter.name}: no pre-compiled URLs")
                        continue
                    }
                    val result = downloadManager.downloadFilterList(filter, forceUpdate = false)
                    if (result.isSuccess) {
                        val paths = result.getOrNull() ?: continue
                        if (filter.category == FilterList.CATEGORY_SECURITY) {
                            paths.triePath?.let { secTrieSb.append(it).append(",") }
                            paths.bloomPath?.let { secBloomSb.append(it).append(",") }
                        } else {
                            paths.triePath?.let { adTrieSb.append(it).append(",") }
                            paths.bloomPath?.let { adBloomSb.append(it).append(",") }
                        }
                        // Update per-filter domainCount so UI shows the correct value
                        if (filter.domainCount != filter.ruleCount) {
                            filterListDao.updateStats(id = filter.id, count = filter.ruleCount, timestamp = System.currentTimeMillis())
                        }
                        totalCount += filter.ruleCount
                    } else {
                        Timber.e("Failed to download filter: ${filter.name}")
                    }
                }

                adTriePaths = adTrieSb.toString().trimEnd(',')
                securityTriePaths = secTrieSb.toString().trimEnd(',')
                adBloomPaths = adBloomSb.toString().trimEnd(',')
                securityBloomPaths = secBloomSb.toString().trimEnd(',')

                compileCosmeticRules(enabledLists)

                _domainCountFlow.value = totalCount
                val elapsed = System.currentTimeMillis() - startTime
                Timber.d("Filter paths loaded in ${elapsed}ms, totalRules=$totalCount")
                Result.success(totalCount)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load filters")
                Result.failure(e)
            }
        }
    }

    private suspend fun compileCosmeticRules(enabledLists: List<FilterList>) = withContext(Dispatchers.IO) {
        try {
            val validLists = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
            if (validLists.isEmpty()) return@withContext

            val cssBuilder = StringBuilder()
            var rulesAdded = 0

            for (filter in validLists) {
                if (filter.cssUrl.isNullOrEmpty()) continue
                val cssFile = File(context.filesDir, "remote_filters/${filter.id}.css")
                if (cssFile.exists() && cssFile.length() > 0) {
                    val cssSnippet = downloadManager.getInjectableCss(cssFile)
                    if (cssSnippet.isNotEmpty()) {
                        cssBuilder.append(cssSnippet)
                        rulesAdded++
                    }
                }
            }

            if (rulesAdded > 0) {
                val finalCssFile = File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css")
                finalCssFile.parentFile?.mkdirs()
                finalCssFile.writeText(cssBuilder.toString())
                Timber.d("Wrote cosmetic CSS (${cssBuilder.length} bytes)")
            } else {
                File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css").delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to compile cosmetic rules")
        }
    }

    suspend fun forceUpdateAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Sync the latest metadata for all built-in filters to the DB
            fetchAndSyncRemoteFilterLists()

            // Fetch the enabled built-in filters (now with updated ruleCounts and URLs)
            val enabledBuiltIn = filterListDao.getEnabled().filter { it.isBuiltIn }

            var totalCount = 0

            for (filter in enabledBuiltIn) {
                // Force download the binary files from the remote server
                val result = downloadManager.downloadFilterList(filter, forceUpdate = true)
                if (result.isSuccess) {
                    filterListDao.updateStats(id = filter.id, count = filter.ruleCount, timestamp = System.currentTimeMillis())
                    totalCount += filter.ruleCount
                } else {
                    Timber.e("Failed to force update built-in filter: ${filter.name}")
                }
            }
            
            // Reload into the Go engine
            loadAllEnabledFilters()
            Result.success(totalCount)
        } catch (e: Exception) {
            Timber.e(e, "Failed to force update all enabled filters")
            Result.failure(e)
        }
    }

    suspend fun updateSingleFilter(filter: FilterList): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (filter.isBuiltIn) {
                // Fetch the latest remote configs
                fetchAndSyncRemoteFilterLists()
                
                // Get the updated entity from DB to have the latest ruleCount & URLs
                val updatedFilter = filterListDao.getById(filter.id) ?: filter
                
                val result = downloadManager.downloadFilterList(updatedFilter, forceUpdate = true)
                if (result.isSuccess) {
                    filterListDao.updateStats(id = updatedFilter.id, count = updatedFilter.ruleCount, timestamp = System.currentTimeMillis())
                    loadAllEnabledFilters()
                    Result.success(updatedFilter.ruleCount)
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
                }
            } else {
                val result = downloadManager.downloadFilterList(filter, forceUpdate = true)
                if (result.isSuccess) {
                    filterListDao.updateStats(id = filter.id, count = filter.ruleCount, timestamp = System.currentTimeMillis())
                    loadAllEnabledFilters()
                    Result.success(filter.ruleCount)
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
                }
            }
        } catch (e: Exception) {
            Timber.d("Failed to update ${filter.name}: $e")
            Result.failure(e)
        }
    }

    suspend fun findBlockingFilterLists(targetDomain: String): List<String> = withContext(Dispatchers.IO) {
        val enabledLists = filterListDao.getEnabled()
        if (enabledLists.isEmpty()) return@withContext emptyList()

        val matchedListNames = mutableListOf<String>()
        for (filter in enabledLists) {
            val trieFile = File(context.filesDir, "remote_filters/${filter.id}.trie")
            if (!trieFile.exists() || trieFile.length() == 0L) continue
            try {
                if (tunnel.Tunnel.checkDomainInTrieFile(trieFile.absolutePath, targetDomain)) {
                    matchedListNames.add(filter.name)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error scanning trie for ${filter.name}")
            }
        }
        return@withContext matchedListNames
    }

    suspend fun getDomainPreview(filter: FilterList, limit: Int = 100): List<String> = emptyList()

    suspend fun checkDomainInFilter(filterId: Long, domain: String): Boolean = withContext(Dispatchers.IO) {
        val trieFile = File(context.filesDir, "remote_filters/$filterId.trie")
        if (!trieFile.exists() || trieFile.length() == 0L) return@withContext false
        
        return@withContext try {
            tunnel.Tunnel.checkDomainInTrieFile(trieFile.absolutePath, domain)
        } catch (e: Exception) {
            Timber.e(e, "Error scanning trie for $filterId regarding $domain")
            false
        }
    }

    suspend fun validateFilterUrl(url: String): Result<Boolean> = Result.success(true)
}
