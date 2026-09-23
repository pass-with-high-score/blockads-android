package app.pwhs.blockads.data.entities

import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.dao.inMemoryDb
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.repository.FilterListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileManagerTest {

    private lateinit var db: AppDatabase
    private val prefs = mockk<AppPreferences>(relaxed = true)
    private val repo = mockk<FilterListRepository>(relaxed = true)
    private lateinit var manager: ProfileManager

    @Before
    fun setUp() {
        db = inMemoryDb()
        coEvery { repo.loadAllEnabledFilters() } returns Result.success(0)
        manager = ProfileManager(db.protectionProfileDao(), db.filterListDao(), prefs, repo)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addFilters(vararg urls: String, enabled: Boolean = false) = urls.map {
        db.filterListDao().insert(FilterList(name = it, url = it, isEnabled = enabled))
    }

    private suspend fun enabledUrls() = db.filterListDao().getEnabled().map { it.url }.toSet()

    @Test
    fun `first seed creates every preset and activates Default`() = runTest {
        addFilters(*(ProfileManager.STRICT_FAMILY_FILTER_URLS + "https://unrelated.example").toTypedArray(), enabled = true)

        manager.seedPresetsIfNeeded()

        val profiles = db.protectionProfileDao().getAllSync()
        assertEquals(
            setOf(
                ProtectionProfile.TYPE_DEFAULT, ProtectionProfile.TYPE_STRICT, ProtectionProfile.TYPE_FAMILY,
                ProtectionProfile.TYPE_GAMING, ProtectionProfile.TYPE_STRICT_FAMILY,
            ),
            profiles.map { it.profileType }.toSet(),
        )
        val active = profiles.single { it.isActive }
        assertEquals(ProtectionProfile.TYPE_DEFAULT, active.profileType)
        assertEquals(ProfileManager.DEFAULT_FILTER_URLS, enabledUrls())
        coVerify { prefs.setActiveProfileId(active.id) }
        coVerify { prefs.setSafeSearchEnabled(false) }
        coVerify { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `later seeds only add missing presets and never switch`() = runTest {
        val dao = db.protectionProfileDao()
        val custom = dao.insert(ProtectionProfile(name = "Mine", profileType = ProtectionProfile.TYPE_CUSTOM, isActive = true))
        dao.insert(ProtectionProfile(name = "Default", profileType = ProtectionProfile.TYPE_DEFAULT))

        manager.seedPresetsIfNeeded()

        val profiles = dao.getAllSync()
        assertEquals(6, profiles.size)
        assertEquals(1, profiles.count { it.profileType == ProtectionProfile.TYPE_DEFAULT })
        assertEquals(custom, dao.getActive()?.id)
        coVerify(exactly = 0) { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `switching saves the outgoing profile and applies the target`() = runTest {
        val dao = db.protectionProfileDao()
        addFilters("https://a.example", "https://b.example", "https://c.example")
        db.filterListDao().getAllSync().first { it.url == "https://a.example" }.let {
            db.filterListDao().setEnabled(it.id, true)
        }
        val from = dao.insert(ProtectionProfile(name = "From", profileType = ProtectionProfile.TYPE_CUSTOM, isActive = true))
        val to = dao.insert(
            ProtectionProfile(
                name = "To", profileType = ProtectionProfile.TYPE_CUSTOM,
                enabledFilterUrls = " https://b.example , https://c.example,,",
                safeSearchEnabled = true, youtubeRestrictedMode = true,
            )
        )

        manager.switchToProfile(to)

        assertEquals("https://a.example", dao.getById(from)!!.enabledFilterUrls)
        assertEquals(to, dao.getActive()?.id)
        assertEquals(1, dao.getAllSync().count { it.isActive })
        assertEquals(setOf("https://b.example", "https://c.example"), enabledUrls())
        coVerify { prefs.setActiveProfileId(to) }
        coVerify { prefs.setSafeSearchEnabled(true) }
        coVerify { prefs.setYoutubeRestrictedMode(true) }
        coVerify { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `preset with no saved urls falls back to its type defaults`() = runTest {
        addFilters(*ProfileManager.STRICT_FILTER_URLS.toTypedArray())
        val gaming = db.protectionProfileDao().insert(
            ProtectionProfile(name = "Gaming", profileType = ProtectionProfile.TYPE_GAMING)
        )
        manager.switchToProfile(gaming)
        assertEquals(ProfileManager.GAMING_FILTER_URLS, enabledUrls())
    }

    @Test
    fun `custom profile with no saved urls disables every filter`() = runTest {
        addFilters("https://a.example", enabled = true)
        val custom = db.protectionProfileDao().insert(ProtectionProfile(name = "Empty", profileType = ProtectionProfile.TYPE_CUSTOM))
        manager.switchToProfile(custom)
        assertTrue(enabledUrls().isEmpty())
    }

    @Test
    fun `switching to a missing profile does nothing`() = runTest {
        manager.switchToProfile(404)
        coVerify(exactly = 0) { prefs.setActiveProfileId(any()) }
        coVerify(exactly = 0) { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `saveActiveProfileFilterUrls stores the enabled urls on the active profile`() = runTest {
        val dao = db.protectionProfileDao()
        manager.saveActiveProfileFilterUrls()

        addFilters("https://a.example", enabled = true)
        val id = dao.insert(ProtectionProfile(name = "P", profileType = ProtectionProfile.TYPE_CUSTOM, isActive = true))
        manager.saveActiveProfileFilterUrls()
        assertEquals("https://a.example", dao.getById(id)!!.enabledFilterUrls)

        manager.saveActiveProfileFilterUrls()
        assertEquals("https://a.example", dao.getById(id)!!.enabledFilterUrls)
    }

    @Test
    fun `filter urls per type`() {
        assertEquals(ProfileManager.DEFAULT_FILTER_URLS, manager.getFilterUrlsForType(ProtectionProfile.TYPE_DEFAULT))
        assertEquals(ProfileManager.STRICT_FILTER_URLS, manager.getFilterUrlsForType(ProtectionProfile.TYPE_STRICT))
        assertEquals(ProfileManager.FAMILY_FILTER_URLS, manager.getFilterUrlsForType(ProtectionProfile.TYPE_FAMILY))
        assertEquals(ProfileManager.GAMING_FILTER_URLS, manager.getFilterUrlsForType(ProtectionProfile.TYPE_GAMING))
        assertEquals(ProfileManager.STRICT_FAMILY_FILTER_URLS, manager.getFilterUrlsForType(ProtectionProfile.TYPE_STRICT_FAMILY))
        assertEquals(emptySet<String>(), manager.getFilterUrlsForType(ProtectionProfile.TYPE_CUSTOM))
        assertTrue(ProfileManager.STRICT_FAMILY_FILTER_URLS.containsAll(ProfileManager.FAMILY_FILTER_URLS))
    }
}
