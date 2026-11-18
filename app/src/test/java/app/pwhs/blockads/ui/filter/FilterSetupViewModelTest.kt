package app.pwhs.blockads.ui.filter

import android.app.Application
import app.cash.turbine.test
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.datastore.prefs.FilterPreferences
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.repository.CustomFilterManager
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.keepHot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class FilterSetupViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val lists = MutableStateFlow(
        listOf(
            FilterList(id = 1, name = "EasyList", url = "https://easylist", description = "Ads", isBuiltIn = true),
            FilterList(id = 2, name = "Mine", url = "https://mine", description = "Custom trackers"),
        )
    )
    private val repo: FilterListRepository = mockk(relaxed = true)
    private val dao: FilterListDao = mockk(relaxed = true) { every { getAll() } returns lists }
    private val customFilters: CustomFilterManager = mockk(relaxed = true)
    private val vm by lazy { FilterSetupViewModel(repo, dao, customFilters, mockk<Application>(relaxed = true)) }

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
        coEvery { dao.getByUrl(any()) } returns null
        coEvery { dao.getByOriginalUrl(any()) } returns null
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `init seeds defaults`() {
        vm
        coVerify(exactly = 1) { repo.seedDefaultsIfNeeded() }
    }

    @Test
    fun `search matches name or description case-insensitively`() = runTest {
        keepHot(vm.filteredFilterLists)
        val expectedInitial = if (BuildConfig.IS_STORE) 1 else 2
        assertEquals(expectedInitial, vm.filteredFilterLists.value.size)
        vm.setSearchQuery("TRACKERS")
        assertEquals(listOf(2L), vm.filteredFilterLists.value.map { it.id })
        vm.setSearchQuery("easy")
        val expectedEasy = if (BuildConfig.IS_STORE) emptyList() else listOf(1L)
        assertEquals(expectedEasy, vm.filteredFilterLists.value.map { it.id })
        vm.setSearchQuery("  ")
        assertEquals(expectedInitial, vm.filteredFilterLists.value.size)
    }

    @Test
    fun `toggling saves the filter state, reloads and restarts`() {
        vm.toggleFilterList(lists.value[0])
        coVerifyOrder {
            dao.setEnabled(1, false)
            repo.loadAllEnabledFilters()
        }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `adding a known url is refused as a duplicate`() = runTest {
        coEvery { dao.getByUrl("https://mine") } returns lists.value[1]
        vm.events.test {
            vm.addFilterList("x", "  https://mine  ")
            assertEquals(UiEvent.ToastRes(R.string.filter_error_duplicate_url), awaitItem())
        }
        coVerify(exactly = 0) { customFilters.addCustomFilter(any<List<String>>(), any(), any(), any()) }
    }

    @Test
    fun `adding a remote filter saves the profile, reloads and signals success`() = runTest {
        coEvery { dao.getByUrl(any()) } returns null
        coEvery { customFilters.addCustomFilter(listOf("https://new"), "New", any(), any(), any()) } returns Result.success(FilterList(name = "New", url = "https://new"))
        vm.filterAddedEvent.test {
            vm.events.test {
                vm.addFilterList(" New ", " https://new ")
                assertEquals(UiEvent.ToastRes(R.string.settings_add, listOf(":  New ")), awaitItem())
            }
            awaitItem()
        }
        assertFalse(vm.isAddingCustomFilter.value)
        coVerify { repo.loadAllEnabledFilters() }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `a failed remote add reports failure and changes nothing`() = runTest {
        coEvery { dao.getByUrl(any()) } returns null
        coEvery { customFilters.addCustomFilter(any<List<String>>(), any(), any(), any(), any()) } returns Result.failure(RuntimeException("404"))
        vm.events.test {
            vm.addFilterList("n", "https://bad")
            assertEquals(UiEvent.ToastRes(R.string.filter_update_failed), awaitItem())
        }
        assertFalse(vm.isAddingCustomFilter.value)
    }

    @Test
    fun `a local build is enqueued instead of added inline`() = runTest {
        coEvery { dao.getByUrl(any()) } returns null
        vm.filterAddedEvent.test {
            vm.events.test {
                vm.addFilterList("Local", "https://local", buildLocally = true)
                assertEquals(UiEvent.ToastRes(R.string.filter_compile_enqueued), awaitItem())
            }
            awaitItem()
        }
        coVerify { customFilters.enqueueLocalCompile("https://local", "Local", any()) }
        coVerify(exactly = 0) { customFilters.addCustomFilter(any<List<String>>(), any(), any(), any()) }
    }

    @Test
    fun `built-in lists cannot be deleted`() {
        vm.deleteFilterList(lists.value[0])
        coVerify(exactly = 0) { customFilters.deleteCustomFilter(any()) }
    }

    @Test
    fun `deleting a custom list cleans up, reloads and restarts`() {
        vm.deleteFilterList(lists.value[1])
        coVerifyOrder {
            customFilters.deleteCustomFilter(lists.value[1])
            repo.loadAllEnabledFilters()
        }
        verify { ServiceController.requestRestart(any()) }
    }

    private fun custom(id: Long, enabled: Boolean = true, local: Boolean = false, rules: Int = 0) = FilterList(
        id = id, name = "c$id", url = "https://c$id", isEnabled = enabled, ruleCount = rules,
        trieUrl = if (local) "local://t" else "https://t", bloomUrl = if (local) "local://b" else "https://b",
    )

    private fun stubMixedUpdate(): Triple<FilterList, FilterList, FilterList> {
        val remote = custom(3)
        val local = custom(4, local = true)
        val disabled = custom(5, enabled = false)
        coEvery { repo.forceUpdateAllEnabledFilters(any()) } returns Result.success(100)
        coEvery { dao.getAllNonBuiltIn() } returns listOf(remote, local, disabled)
        coEvery { customFilters.updateCustomFilter(remote) } returns Result.success(remote.copy(ruleCount = 7))
        return Triple(remote, local, disabled)
    }

    @Test
    fun `update all recompiles remote lists, skips disabled and enqueues local lists`() = runTest {
        val (_, local, disabled) = stubMixedUpdate()
        vm.events.test {
            vm.updateAllFilters()
            assertEquals(UiEvent.ToastRes(R.string.filter_compile_enqueued), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { customFilters.enqueueRecompileLocally(local) }
        coVerify(exactly = 0) { customFilters.updateCustomFilter(disabled) }
        coVerify { repo.loadAllEnabledFilters() }
        assertFalse(vm.isUpdatingFilter.value)
    }

    @Test
    fun `update all without local lists reports the summed built-in and custom counts`() = runTest {
        val remote = custom(3)
        coEvery { repo.forceUpdateAllEnabledFilters(any()) } returns Result.success(100)
        coEvery { dao.getAllNonBuiltIn() } returns listOf(remote)
        coEvery { customFilters.updateCustomFilter(remote) } returns Result.success(remote.copy(ruleCount = 7))
        vm.events.test {
            vm.updateAllFilters()
            assertEquals(UiEvent.ToastRes(R.string.filter_updated, listOf(107)), awaitItem())
        }
    }

    @Ignore("known bug: events has a 1-slot buffer and toast() uses tryEmit, so the second of two back-to-back toasts is dropped")
    @Test
    fun `update all with local lists reports both the enqueue and the updated count`() = runTest {
        stubMixedUpdate()
        vm.events.test {
            vm.updateAllFilters()
            assertEquals(UiEvent.ToastRes(R.string.filter_compile_enqueued), awaitItem())
            assertEquals(UiEvent.ToastRes(R.string.filter_updated, listOf(107)), awaitItem())
        }
    }

    @Test
    fun `update all reports failure when nothing updated`() = runTest {
        coEvery { repo.forceUpdateAllEnabledFilters(any()) } returns Result.failure(RuntimeException("offline"))
        coEvery { dao.getAllNonBuiltIn() } returns emptyList()
        vm.events.test {
            vm.updateAllFilters()
            assertEquals(UiEvent.ToastRes(R.string.filter_update_failed), awaitItem())
        }
    }

    @Test
    fun `update all still reports custom successes when the built-in update fails`() = runTest {
        val remote = custom(3)
        coEvery { repo.forceUpdateAllEnabledFilters(any()) } returns Result.failure(RuntimeException("offline"))
        coEvery { dao.getAllNonBuiltIn() } returns listOf(remote)
        coEvery { customFilters.updateCustomFilter(remote) } returns Result.success(remote.copy(ruleCount = 5))
        vm.events.test {
            vm.updateAllFilters()
            assertEquals(UiEvent.ToastRes(R.string.filter_updated, listOf(5)), awaitItem())
        }
    }

    @Test
    fun `toggleStarredFilter calls appPreferences filter toggleStarredFilter`() = runTest {
        val filterPrefs: FilterPreferences = mockk(relaxed = true)
        val appPrefs: AppPreferences = mockk(relaxed = true) {
            every { filter } returns filterPrefs
            every { filterBuildMode } returns flowOf(AppPreferences.BUILD_MODE_ONLINE)
        }
        val testVm = FilterSetupViewModel(repo, dao, customFilters, mockk<Application>(relaxed = true), appPrefs)
        val filter = FilterList(id = 1, name = "Test", url = "https://example.com/filter.txt")
        testVm.toggleStarredFilter(filter)
        coVerify(exactly = 1) { filterPrefs.toggleStarredFilter("https://example.com/filter.txt") }
    }

    @Test
    fun `filterBuildMode exposes preference value`() = runTest {
        val filterPrefs: FilterPreferences = mockk(relaxed = true)
        val appPrefs: AppPreferences = mockk(relaxed = true) {
            every { filter } returns filterPrefs
            every { filterBuildMode } returns flowOf(AppPreferences.BUILD_MODE_LOCAL)
        }
        val testVm = FilterSetupViewModel(repo, dao, customFilters, mockk<Application>(relaxed = true), appPrefs)
        keepHot(testVm.filterBuildMode)
        assertEquals(AppPreferences.BUILD_MODE_LOCAL, testVm.filterBuildMode.value)
    }

    @Test
    fun `tag selection, creation and deletion works as expected`() = runTest {
        val appPrefs: AppPreferences = mockk(relaxed = true) {
            every { customFilterTags } returns flowOf(setOf("Ads", "Social"))
        }
        val testVm = FilterSetupViewModel(repo, dao, customFilters, mockk<Application>(relaxed = true), appPrefs)
        keepHot(testVm.customFilterTags)

        // Select tag
        assertNull(testVm.selectedTag.value)
        testVm.selectTag("Ads")
        assertEquals("Ads", testVm.selectedTag.value)

        // Create tag
        testVm.createTag("Security")
        coVerify(exactly = 1) { appPrefs.addCustomFilterTag("Security") }
        assertEquals("Security", testVm.selectedTag.value)

        // Delete selected tag
        testVm.deleteTag("Security")
        coVerify(exactly = 1) { appPrefs.removeCustomFilterTag("Security") }
        assertNull(testVm.selectedTag.value)
    }
}
