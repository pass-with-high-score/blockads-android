package app.pwhs.blockads.data

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
            FilterList(name = "ABPVN", url = "https://abpvn.com/android/abpvn.txt", isEnabled = true),
            FilterList(name = "HostsVN", url = "https://raw.githubusercontent.com/bigdargon/hostsVN/master/hosts", isEnabled = true),
            FilterList(name = "AdGuard DNS", url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", isEnabled = false),
        )
    }

    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedDomains = ConcurrentHashMap.newKeySet<String>()

    val domainCount: Int get() = blockedDomains.size

    fun isBlocked(domain: String): Boolean {
        // Check whitelist first — whitelisted domains are always allowed
        if (whitelistedDomains.contains(domain)) return false
        var wd = domain
        while (wd.contains('.')) {
            wd = wd.substringAfter('.')
            if (whitelistedDomains.contains(wd)) return false
        }

        // Check blocklist
        if (blockedDomains.contains(domain)) return true
        var d = domain
        while (d.contains('.')) {
            d = d.substringAfter('.')
            if (blockedDomains.contains(d)) return true
        }
        return false
    }

    suspend fun loadWhitelist() {
        val domains = whitelistDomainDao.getAllDomains()
        whitelistedDomains.clear()
        whitelistedDomains.addAll(domains.map { it.lowercase() })
        Log.d(TAG, "Loaded ${whitelistedDomains.size} whitelisted domains")
    }

    /**
     * Seeds default filter lists if the database is empty.
     */
    suspend fun seedDefaultsIfNeeded() {
        if (filterListDao.count() == 0) {
            DEFAULT_LISTS.forEach { filterListDao.insert(it) }
            Log.d(TAG, "Seeded ${DEFAULT_LISTS.size} default filter lists")
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
            Log.d(TAG, "Total unique domains loaded: ${blockedDomains.size}")

            Result.success(blockedDomains.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load filters", e)
            Result.failure(e)
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
        File(context.filesDir, CACHE_DIR).deleteRecursively()
    }
}
