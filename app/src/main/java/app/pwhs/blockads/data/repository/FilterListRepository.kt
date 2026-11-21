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
    private val downloadManager: FilterDownloadManager,
    private val appPreferences: app.pwhs.blockads.data.datastore.AppPreferences? = null,
    private val seeder: FilterListSeeder = FilterListSeeder(context, filterListDao, client, appPreferences)
) {

    companion object {
        private const val CACHE_DIR = "filter_cache"
        private const val FILTER_CACHE_SCHEMA_MARKER = ".filter_cache_v713"
        private const val COMPILED_SIGNATURE_FILE = ".compiled_signature_v1"
        const val BLOCK_REASON_CUSTOM_RULE = "CUSTOM_RULE"
        const val BLOCK_REASON_FILTER_LIST = "FILTER_LIST"
        const val BLOCK_REASON_SECURITY = "SECURITY"
        const val BLOCK_REASON_FIREWALL = "FIREWALL"
        const val BLOCK_REASON_UPSTREAM_DNS = "upstream_dns"
    }

    // Paths to pre-compiled binary files for Go Native Engine (CSV strings)
    @Volatile
    private var adTriePaths: String = ""

    @Volatile
    private var securityTriePaths: String = ""

    @Volatile
    private var adBloomPaths: String = ""

    @Volatile
    private var securityBloomPaths: String = ""

    @Volatile
    private var ipBlocklistPaths: String = ""

    fun getIpBlocklistPaths(): String = ipBlocklistPaths

    @Volatile
    private var lastCompiledSignature: String = ""

    private val loadMutex = Mutex()

    init {
        File(context.filesDir, "remote_filters").mkdirs()
    }

    private val whitelistedDomains = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var customRuleMatcher: CustomRuleMatcher = CustomRuleMatcher.EMPTY

    private val _domainCountFlow = MutableStateFlow(0)
    val domainCountFlow: StateFlow<Int> = _domainCountFlow.asStateFlow()
    val domainCount: Int get() = _domainCountFlow.value

    fun getAdTriePath(): String = adTriePaths
    fun getSecurityTriePath(): String = securityTriePaths
    fun getAdBloomPath(): String = adBloomPaths
    fun getSecurityBloomPath(): String = securityBloomPaths

    fun getScriptletsPath(): String? {
        val file = File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun getCosmeticCssPath(): String? {
        val file = File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun getFilterModulesPath(): String? {
        val file = File(context.filesDir, "$CACHE_DIR/filter_modules.json")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /** Returns adPathPatterns from browser_rules.json as newline-separated string. */
    fun getAdPathPatterns(): String {
        return try {
            val json = context.assets.open("browser_rules.json").bufferedReader().use { it.readText() }
            // Simple extraction: find "adPathPatterns":[...] and parse the strings
            val match = """"adPathPatterns"\s*:\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(json)?.groupValues?.get(1) ?: return ""
            """"(.*?)"""".toRegex().findAll(match)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } catch (e: Exception) {
            Timber.w(e, "Failed to read ad path patterns from browser_rules.json")
            ""
        }
    }

    private inline fun checkDomainAndParents(
        domain: String,
        checker: (String) -> Boolean
    ): Boolean {
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
        return when (customRuleMatcher.matchGlobal(domain)) {
            CustomRuleMatcher.MATCH_ALLOW -> false
            CustomRuleMatcher.MATCH_BLOCK -> true
            else -> {
                if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) return false
                false
            }
        }
    }

    fun hasCustomRule(domain: String): Long {
        return when (customRuleMatcher.matchGlobal(domain)) {
            CustomRuleMatcher.MATCH_ALLOW -> 0L
            CustomRuleMatcher.MATCH_BLOCK -> {
                if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) 0L else 1L
            }
            else -> {
                if (checkDomainAndParents(domain) { whitelistedDomains.contains(it) }) 0L else -1L
            }
        }
    }

    fun checkAppRule(domain: String, packageName: String): Long {
        return customRuleMatcher.matchApp(domain, packageName).toLong()
    }

    fun getBlockReason(domain: String): String {
        return when (customRuleMatcher.matchGlobal(domain)) {
            CustomRuleMatcher.MATCH_ALLOW -> ""
            CustomRuleMatcher.MATCH_BLOCK -> BLOCK_REASON_CUSTOM_RULE
            else -> ""
        }
    }

    suspend fun loadCustomRules() {
        val enabledRules = customDnsRuleDao.getEnabledRules()
        val compiled = CustomRuleMatcher.compile(enabledRules)
        customRuleMatcher = compiled
        Timber.d(
            "Loaded ${compiled.globalBlockCount} block + ${compiled.globalAllowCount} allow custom rules, " +
                "${compiled.appRuleSetCount} per-app rule sets"
        )
    }

    suspend fun loadWhitelist() {
        val domains = whitelistDomainDao.getEnabledDomains()
        whitelistedDomains.clear()
        whitelistedDomains.addAll(domains.map { it.lowercase() })
        Timber.d("Loaded ${whitelistedDomains.size} whitelisted domains")
    }

    suspend fun seedDefaultsIfNeeded(allowInStore: Boolean = false) = seeder.seedDefaultsIfNeeded(allowInStore)

    suspend fun fetchAndSyncRemoteFilterLists() = seeder.fetchAndSyncRemoteFilterLists()

    internal fun parseRemoteFilterJson(json: String): List<app.pwhs.blockads.data.remote.models.FilterList> =
        seeder.parseFilterJson(json)

    private suspend fun invalidateLegacyFilterCacheIfNeeded() {
        val marker = File(context.filesDir, FILTER_CACHE_SCHEMA_MARKER)
        if (marker.exists()) return
        try {
            File(context.filesDir, CACHE_DIR).deleteRecursively()
            lastCompiledSignature = ""
            val remoteDir = File(context.filesDir, "remote_filters").apply { mkdirs() }
            val allFilters = filterListDao.getAllSync()
            val localFilters = allFilters.filter { it.trieUrl.startsWith("local://") }
            val localIds = localFilters.map { it.id }.toSet()

            remoteDir.listFiles()?.forEach { file ->
                val idPrefix = file.name.substringBefore('.').toLongOrNull()
                if (file.name.endsWith(".modules.json") || idPrefix == null || idPrefix !in localIds) {
                    file.delete()
                }
            }

            val compiler = CustomFilterLocalCompiler(context, client, filterListDao)
            for (filter in localFilters) {
                val rawUrl = filter.originalUrl.ifEmpty { filter.url }.trim()
                if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    runCatching { compiler.recompileOrUpdateLocally(filter) }
                }
            }

            marker.writeText("713")
            Timber.d("Invalidated legacy filter cache and wrote $FILTER_CACHE_SCHEMA_MARKER")
        } catch (t: Throwable) {
            Timber.w(t, "Failed to invalidate legacy filter cache")
        }
    }

    suspend fun loadAllEnabledFilters(): Result<Int> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            try {
                invalidateLegacyFilterCacheIfNeeded()
                val enabledLists = filterListDao.getEnabled()
                if (enabledLists.isEmpty()) {
                    adTriePaths = ""
                    securityTriePaths = ""
                    adBloomPaths = ""
                    securityBloomPaths = ""
                    ipBlocklistPaths = ""
                    lastCompiledSignature = ""
                    File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css").delete()
                    File(context.filesDir, "$CACHE_DIR/cosmetic_engine_merged.css").delete()
                    File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt").delete()
                    File(context.filesDir, "$CACHE_DIR/filter_modules.json").delete()
                    File(context.filesDir, "$CACHE_DIR/$COMPILED_SIGNATURE_FILE").delete()
                    _domainCountFlow.value = 0
                    return@withContext Result.success(0)
                }

                val startTime = System.currentTimeMillis()
                val adTrieSb = StringBuilder()
                val secTrieSb = StringBuilder()
                val adBloomSb = StringBuilder()
                val secBloomSb = StringBuilder()
                val ipsSb = StringBuilder()
                var totalCount = 0

                for (filter in enabledLists) {
                    if (filter.bloomUrl.isEmpty() || filter.trieUrl.isEmpty()) {
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
                        paths.ipsPath?.let { ipsSb.append(it).append("\n") }
                        // Update per-filter domainCount so UI shows the correct value
                        if (filter.domainCount != filter.ruleCount) {
                            filterListDao.updateStats(
                                id = filter.id,
                                count = filter.ruleCount,
                                timestamp = System.currentTimeMillis()
                            )
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
                ipBlocklistPaths = ipsSb.toString().trim()

                val signature = buildSidecarSignature(enabledLists)
                if (isSidecarCacheValid(signature, enabledLists)) {
                    lastCompiledSignature = signature
                    Timber.d("Compiled filter sidecars up-to-date, skipping recompilation")
                } else {
                    compileCosmeticRules(enabledLists)
                    compileScriptletRules(enabledLists)
                    compileModuleRules(enabledLists)
                    if (signature.isNotEmpty()) {
                        val sigFile = File(context.filesDir, "$CACHE_DIR/$COMPILED_SIGNATURE_FILE")
                        sigFile.parentFile?.mkdirs()
                        sigFile.writeText(signature)
                        lastCompiledSignature = signature
                    } else {
                        lastCompiledSignature = ""
                        File(context.filesDir, "$CACHE_DIR/$COMPILED_SIGNATURE_FILE").delete()
                    }
                }

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

    private fun buildSidecarSignature(enabledLists: List<FilterList>): String {
        val sb = StringBuilder()
        for (filter in enabledLists) {
            if (filter.category == FilterList.CATEGORY_SECURITY) continue
            val css = File(context.filesDir, "remote_filters/${filter.id}.css")
            val scr = File(context.filesDir, "remote_filters/${filter.id}.scriptlets")
            val mod = File(context.filesDir, "remote_filters/${filter.id}.modules.json")
            sb.append(filter.id).append(':')
                .append(if (css.exists()) "${css.length()}@${css.lastModified()}" else "0").append(':')
                .append(if (scr.exists()) "${scr.length()}@${scr.lastModified()}" else "0").append(':')
                .append(if (mod.exists()) "${mod.length()}@${mod.lastModified()}" else "0").append(';')
        }
        return sb.toString()
    }

    private fun isSidecarCacheValid(signature: String, enabledLists: List<FilterList>): Boolean {
        if (signature.isEmpty()) return false
        val sigFile = File(context.filesDir, "$CACHE_DIR/$COMPILED_SIGNATURE_FILE")
        if (!sigFile.exists()) {
            lastCompiledSignature = ""
            return false
        }
        val cachedSig = lastCompiledSignature.ifEmpty {
            runCatching { sigFile.readText() }.getOrDefault("")
        }
        if (cachedSig != signature) return false

        val nonSecurity = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
        val expectsCss = nonSecurity.any { filter ->
            File(context.filesDir, "remote_filters/${filter.id}.css").let { it.exists() && it.length() > 0L }
        }
        if (expectsCss && !File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css").exists()) return false

        val expectsScriptlets = nonSecurity.any { filter ->
            File(context.filesDir, "remote_filters/${filter.id}.scriptlets").let { it.exists() && it.length() > 0L }
        }
        if (expectsScriptlets && !File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt").exists()) return false

        return true
    }

    private suspend fun compileScriptletRules(enabledLists: List<FilterList>) =
        withContext(Dispatchers.IO) {
            val outFile = File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt")
            val tmpFile = File(context.filesDir, "$CACHE_DIR/scriptlets_combined.txt.tmp")
            try {
                val validLists = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
                if (validLists.isEmpty()) {
                    outFile.delete()
                    tmpFile.delete()
                    return@withContext
                }

                outFile.parentFile?.mkdirs()
                var added = 0
                tmpFile.bufferedWriter().use { writer ->
                    for (filter in validLists) {
                        val f = File(context.filesDir, "remote_filters/${filter.id}.scriptlets")
                        if (f.exists() && f.length() > 0) {
                            f.forEachLine { line ->
                                writer.write(line)
                                writer.write("\n")
                            }
                            added++
                        }
                    }
                }

                if (added > 0) {
                    replaceFileAtomically(tmpFile, outFile)
                    Timber.d("Wrote scriptlets ($added lists, ${outFile.length()} bytes)")
                } else {
                    tmpFile.delete()
                    outFile.delete()
                }
            } catch (t: Throwable) {
                tmpFile.delete()
                Timber.e(t, "Failed to compile scriptlet rules")
            }
        }

    private suspend fun compileCosmeticRules(enabledLists: List<FilterList>) =
        withContext(Dispatchers.IO) {
            val finalCssFile = File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css")
            val tmpCssFile = File(context.filesDir, "$CACHE_DIR/cosmetic_rules.css.tmp")
            try {
                val validLists = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
                if (validLists.isEmpty()) {
                    finalCssFile.delete()
                    tmpCssFile.delete()
                    return@withContext
                }

                finalCssFile.parentFile?.mkdirs()
                var rulesAdded = 0
                tmpCssFile.bufferedWriter().use { writer ->
                    for (filter in validLists) {
                        val cssFile = File(context.filesDir, "remote_filters/${filter.id}.css")
                        if (cssFile.exists() && cssFile.length() > 0) {
                            val linesWritten = FilterDownloadManager.writeInjectableCss(cssFile, writer)
                            if (linesWritten > 0) {
                                rulesAdded++
                            }
                        }
                    }
                }

                if (rulesAdded > 0) {
                    replaceFileAtomically(tmpCssFile, finalCssFile)
                    Timber.d("Wrote cosmetic CSS (${finalCssFile.length()} bytes)")
                } else {
                    tmpCssFile.delete()
                    finalCssFile.delete()
                }
            } catch (t: Throwable) {
                tmpCssFile.delete()
                Timber.e(t, "Failed to compile cosmetic rules")
            }
        }

    private suspend fun compileModuleRules(enabledLists: List<FilterList>) =
        withContext(Dispatchers.IO) {
            val outFile = File(context.filesDir, "$CACHE_DIR/filter_modules.json")
            val tmpFile = File(context.filesDir, "$CACHE_DIR/filter_modules.json.tmp")
            try {
                val validLists = enabledLists.filter { it.category != FilterList.CATEGORY_SECURITY }
                if (validLists.isEmpty()) {
                    outFile.delete()
                    tmpFile.delete()
                    return@withContext
                }

                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                class DomainAccumulator(
                    val css: MutableSet<String> = linkedSetOf(),
                    val cssExceptions: MutableSet<String> = linkedSetOf(),
                    val injectCss: MutableSet<String> = linkedSetOf(),
                    val blockPaths: MutableSet<String> = linkedSetOf(),
                    val removeParams: MutableSet<String> = linkedSetOf()
                )
                val mergedDomains = mutableMapOf<String, DomainAccumulator>()
                val mergedGlobalPaths = linkedSetOf<String>()
                val mergedGlobalCssExceptions = linkedSetOf<String>()
                val mergedGlobalInjectCss = linkedSetOf<String>()
                val mergedGlobalRemoveParams = linkedSetOf<String>()

                for (filter in validLists) {
                    val modFile = File(context.filesDir, "remote_filters/${filter.id}.modules.json")
                    if (!modFile.exists() || modFile.length() == 0L) continue
                    val pkg = runCatching {
                        json.decodeFromString<app.pwhs.blockads.data.models.ModuleRulesPackage>(modFile.readText())
                    }.getOrNull() ?: continue

                    // When a .css sidecar exists, domain##selector rules are already compiled into
                    // cosmetic_rules.css and indexed per-host by Go's CosmeticStore.
                    val hasCssSidecar = File(context.filesDir, "remote_filters/${filter.id}.css")
                        .let { it.exists() && it.length() > 0L }

                    mergedGlobalPaths.addAll(pkg.globalBlockPaths)
                    mergedGlobalCssExceptions.addAll(pkg.globalCssExceptions)
                    mergedGlobalInjectCss.addAll(pkg.globalInjectCss)
                    mergedGlobalRemoveParams.addAll(pkg.globalRemoveParams)

                    for ((domain, rules) in pkg.domains) {
                        val effectiveCss = if (hasCssSidecar) emptyList() else rules.css
                        if (effectiveCss.isEmpty() &&
                            rules.cssExceptions.isEmpty() &&
                            rules.injectCss.isEmpty() &&
                            rules.blockPaths.isEmpty() &&
                            rules.removeParams.isEmpty()
                        ) continue
                        val acc = mergedDomains.getOrPut(domain.lowercase()) { DomainAccumulator() }
                        if (effectiveCss.isNotEmpty()) acc.css.addAll(effectiveCss)
                        if (rules.cssExceptions.isNotEmpty()) acc.cssExceptions.addAll(rules.cssExceptions)
                        if (rules.injectCss.isNotEmpty()) acc.injectCss.addAll(rules.injectCss)
                        if (rules.blockPaths.isNotEmpty()) acc.blockPaths.addAll(rules.blockPaths)
                        if (rules.removeParams.isNotEmpty()) acc.removeParams.addAll(rules.removeParams)
                    }
                }

                val hasAnyRules = mergedDomains.isNotEmpty() ||
                    mergedGlobalPaths.isNotEmpty() ||
                    mergedGlobalCssExceptions.isNotEmpty() ||
                    mergedGlobalInjectCss.isNotEmpty() ||
                    mergedGlobalRemoveParams.isNotEmpty()

                if (hasAnyRules) {
                    val outPkg = app.pwhs.blockads.data.models.ModuleRulesPackage(
                        name = "merged_filters",
                        ruleCount = mergedDomains.size + mergedGlobalPaths.size + mergedGlobalRemoveParams.size,
                        domains = mergedDomains.mapValues { (_, acc) ->
                            app.pwhs.blockads.data.models.DomainModuleRules(
                                css = acc.css.toList(),
                                cssExceptions = acc.cssExceptions.toList(),
                                injectCss = acc.injectCss.toList(),
                                blockPaths = acc.blockPaths.toList(),
                                removeParams = acc.removeParams.toList()
                            )
                        },
                        globalCssExceptions = mergedGlobalCssExceptions.toList(),
                        globalInjectCss = mergedGlobalInjectCss.toList(),
                        globalBlockPaths = mergedGlobalPaths.toList(),
                        globalRemoveParams = mergedGlobalRemoveParams.toList()
                    )
                    outFile.parentFile?.mkdirs()
                    tmpFile.writeText(
                        json.encodeToString(app.pwhs.blockads.data.models.ModuleRulesPackage.serializer(), outPkg)
                    )
                    replaceFileAtomically(tmpFile, outFile)
                    Timber.d("Wrote filter modules (${mergedDomains.size} domains, ${mergedGlobalPaths.size} global paths, ${mergedGlobalRemoveParams.size} global remove_params)")
                } else {
                    tmpFile.delete()
                    outFile.delete()
                }
            } catch (t: Throwable) {
                tmpFile.delete()
                outFile.delete()
                Timber.e(t, "Failed to compile filter module rules")
            }
        }

    private fun replaceFileAtomically(tmpFile: File, targetFile: File) {
        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.copyTo(targetFile, overwrite = true)
            tmpFile.delete()
        }
    }

    suspend fun forceUpdateAllEnabledFilters(
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Sync the latest metadata for all built-in filters to the DB
            fetchAndSyncRemoteFilterLists()

            // Fetch the enabled built-in filters (now with updated ruleCounts and URLs)
            val enabledBuiltIn = filterListDao.getEnabled().filter { it.isBuiltIn }
            val total = enabledBuiltIn.size
            var completed = 0

            var totalCount = 0

            for (filter in enabledBuiltIn) {
                // Force download the binary files from the remote server
                val result = downloadManager.downloadFilterList(filter, forceUpdate = true)
                if (result.isSuccess) {
                    filterListDao.updateStats(
                        id = filter.id,
                        count = filter.ruleCount,
                        timestamp = System.currentTimeMillis()
                    )
                    totalCount += filter.ruleCount
                } else {
                    Timber.e("Failed to force update built-in filter: ${filter.name}")
                }
                completed++
                onProgress?.invoke(completed, total)
            }

            // Reload into the Go engine
            invalidateCompiledSidecarCache()
            loadAllEnabledFilters()
            Result.success(totalCount)
        } catch (e: Exception) {
            Timber.e(e, "Failed to force update all enabled filters")
            Result.failure(e)
        }
    }

    private fun invalidateCompiledSidecarCache() {
        lastCompiledSignature = ""
        File(context.filesDir, "$CACHE_DIR/$COMPILED_SIGNATURE_FILE").delete()
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
                    filterListDao.updateStats(
                        id = updatedFilter.id,
                        count = updatedFilter.ruleCount,
                        timestamp = System.currentTimeMillis()
                    )
                    invalidateCompiledSidecarCache()
                    loadAllEnabledFilters()
                    Result.success(updatedFilter.ruleCount)
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("Unknown error"))
                }
            } else {
                val result = downloadManager.downloadFilterList(filter, forceUpdate = true)
                if (result.isSuccess) {
                    filterListDao.updateStats(
                        id = filter.id,
                        count = filter.ruleCount,
                        timestamp = System.currentTimeMillis()
                    )
                    invalidateCompiledSidecarCache()
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

    suspend fun findBlockingFilterLists(targetDomain: String): List<String> =
        withContext(Dispatchers.IO) {
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

    suspend fun checkDomainInFilter(filterId: Long, domain: String): Boolean =
        withContext(Dispatchers.IO) {
            val trieFile = File(context.filesDir, "remote_filters/$filterId.trie")
            if (!trieFile.exists() || trieFile.length() == 0L) return@withContext false

            return@withContext try {
                tunnel.Tunnel.checkDomainInTrieFile(trieFile.absolutePath, domain)
            } catch (e: Exception) {
                Timber.e(e, "Error scanning trie for $filterId regarding $domain")
                false
            }
        }

}
