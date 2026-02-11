package app.pwhs.blockads.data

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * Unit tests for FilterListRepository, especially the Bloom filter optimization.
 */
class FilterListRepositoryTest {

    private lateinit var context: Context
    private lateinit var filterListDao: FilterListDao
    private lateinit var whitelistDomainDao: WhitelistDomainDao
    private lateinit var httpClient: HttpClient
    private lateinit var repository: FilterListRepository
    private lateinit var tempDir: File

    @Before
    fun setup() {
        context = mock(Context::class.java)
        filterListDao = mock(FilterListDao::class.java)
        whitelistDomainDao = mock(WhitelistDomainDao::class.java)
        httpClient = mock(HttpClient::class.java)
        
        // Create a temporary directory for file operations
        tempDir = File.createTempFile("test", "dir").apply {
            delete()
            mkdir()
        }
        
        `when`(context.filesDir).thenReturn(tempDir)
        
        repository = FilterListRepository(
            context = context,
            filterListDao = filterListDao,
            whitelistDomainDao = whitelistDomainDao,
            client = httpClient
        )
    }

    @Test
    fun testBloomFilterBlocksKnownDomains() = runBlocking {
        // Mock the DAO to return enabled filter lists
        val testFilter = FilterList(
            id = 1,
            name = "Test Filter",
            url = "https://example.com/test.txt",
            description = "Test filter list",
            isEnabled = true,
            isBuiltIn = true
        )
        `when`(filterListDao.getEnabled()).thenReturn(listOf(testFilter))
        
        // Create a cache file with test domains
        val cacheDir = File(tempDir, "filter_cache")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${testFilter.url.hashCode()}.txt")
        cacheFile.writeText("""
            # Test filter list
            0.0.0.0 ads.example.com
            0.0.0.0 tracker.example.com
            ||analytics.example.com^
            malware.example.com
        """.trimIndent())
        
        // Load filters (this should build the Bloom filter)
        val result = repository.loadAllEnabledFilters()
        assertTrue(result.isSuccess)
        assertEquals(4, result.getOrNull())
        
        // Test that blocked domains are correctly identified
        assertTrue("ads.example.com should be blocked", repository.isBlocked("ads.example.com"))
        assertTrue("tracker.example.com should be blocked", repository.isBlocked("tracker.example.com"))
        assertTrue("analytics.example.com should be blocked", repository.isBlocked("analytics.example.com"))
        assertTrue("malware.example.com should be blocked", repository.isBlocked("malware.example.com"))
        
        // Test that non-blocked domains are correctly identified
        assertFalse("google.com should not be blocked", repository.isBlocked("google.com"))
        assertFalse("facebook.com should not be blocked", repository.isBlocked("facebook.com"))
    }

    @Test
    fun testBloomFilterParentDomainMatching() = runBlocking {
        val testFilter = FilterList(
            id = 1,
            name = "Test Filter",
            url = "https://example.com/test.txt",
            description = "Test filter list",
            isEnabled = true,
            isBuiltIn = true
        )
        `when`(filterListDao.getEnabled()).thenReturn(listOf(testFilter))
        
        // Create a cache file with parent domain
        val cacheDir = File(tempDir, "filter_cache")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${testFilter.url.hashCode()}.txt")
        cacheFile.writeText("0.0.0.0 ads.example.com")
        
        repository.loadAllEnabledFilters()
        
        // Test that subdomains of blocked domains are also blocked
        assertTrue("ads.example.com should be blocked", repository.isBlocked("ads.example.com"))
        assertTrue("sub.ads.example.com should be blocked", repository.isBlocked("sub.ads.example.com"))
        assertTrue("deep.sub.ads.example.com should be blocked", repository.isBlocked("deep.sub.ads.example.com"))
    }

    @Test
    fun testWhitelistOverridesBlocklist() = runBlocking {
        val testFilter = FilterList(
            id = 1,
            name = "Test Filter",
            url = "https://example.com/test.txt",
            description = "Test filter list",
            isEnabled = true,
            isBuiltIn = true
        )
        `when`(filterListDao.getEnabled()).thenReturn(listOf(testFilter))
        `when`(whitelistDomainDao.getAllDomains()).thenReturn(listOf("ads.example.com"))
        
        // Create a cache file with blocked domain
        val cacheDir = File(tempDir, "filter_cache")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${testFilter.url.hashCode()}.txt")
        cacheFile.writeText("0.0.0.0 ads.example.com")
        
        repository.loadWhitelist()
        repository.loadAllEnabledFilters()
        
        // Test that whitelisted domains are not blocked
        assertFalse("Whitelisted domain should not be blocked", repository.isBlocked("ads.example.com"))
    }

    @Test
    fun testEmptyFilterList() = runBlocking {
        `when`(filterListDao.getEnabled()).thenReturn(emptyList())
        
        val result = repository.loadAllEnabledFilters()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
        
        // All domains should be unblocked
        assertFalse(repository.isBlocked("any.domain.com"))
    }

    @Test
    fun testClearCache() = runBlocking {
        val testFilter = FilterList(
            id = 1,
            name = "Test Filter",
            url = "https://example.com/test.txt",
            description = "Test filter list",
            isEnabled = true,
            isBuiltIn = true
        )
        `when`(filterListDao.getEnabled()).thenReturn(listOf(testFilter))
        
        // Create a cache file
        val cacheDir = File(tempDir, "filter_cache")
        cacheDir.mkdirs()
        val cacheFile = File(cacheDir, "${testFilter.url.hashCode()}.txt")
        cacheFile.writeText("0.0.0.0 ads.example.com")
        
        repository.loadAllEnabledFilters()
        assertTrue(repository.isBlocked("ads.example.com"))
        
        // Clear cache
        repository.clearCache()
        
        // All domains should be unblocked after cache clear
        assertFalse(repository.isBlocked("ads.example.com"))
    }
}
