package app.pwhs.blockads.ui.filter.detail

import android.app.Application
import app.cash.turbine.test
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.entities.ProfileManager
import app.pwhs.blockads.data.repository.CustomFilterManager
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.event.UiEvent
import app.pwhs.blockads.ui.keepHot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class FilterDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val custom = FilterList(
        id = 5, name = "Mine", url = "https://compiled/5", originalUrl = "https://src/list.txt",
        trieUrl = "https://t", bloomUrl = "https://b", ruleCount = 10,
    )
    private val builtIn = FilterList(id = 1, name = "EasyList", url = "https://easylist", isBuiltIn = true)
    private val current = MutableStateFlow<FilterList?>(custom)
    private val dao: FilterListDao = mockk(relaxed = true) { every { getByIdFlow(5) } returns current }
    private val dnsLogDao: DnsLogDao = mockk(relaxed = true) { every { getBlockedCountByReason("5") } returns flowOf(12) }
    private val repo: FilterListRepository = mockk(relaxed = true)
    private val profileManager: ProfileManager = mockk(relaxed = true)
    private val customFilters: CustomFilterManager = mockk(relaxed = true)
    private val vm by lazy {
        FilterDetailViewModel(5, dao, dnsLogDao, repo, profileManager, mockk<Application>(relaxed = true), customFilters)
    }

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
    }

    @After
    fun tearDown() = unmockkAll()

    private fun TestScope.hot() = keepHot(vm.filter, vm.blockedCount)

    @Test
    fun `state exposes the filter and its blocked count`() = runTest {
        hot()
        assertEquals(custom, vm.filter.value)
        assertEquals(12, vm.blockedCount.value)
    }

    @Test
    fun `domain test trims input, ignores blanks and resets on new input`() {
        coEvery { repo.checkDomainInFilter(5, "ads.com") } returns true
        vm.testDomain()
        coVerify(exactly = 0) { repo.checkDomainInFilter(any(), any()) }

        vm.setTestDomainQuery("  ads.com ")
        vm.testDomain()
        assertEquals(true, vm.testDomainResult.value)
        assertFalse(vm.isTestingDomain.value)

        vm.setTestDomainQuery("other")
        assertNull(vm.testDomainResult.value)
        assertEquals("other", vm.testDomainQuery.value)
    }

    @Test
    fun `toggling flips enabled, saves the profile and restarts`() = runTest {
        hot()
        vm.toggleFilter()
        coVerify { dao.setEnabled(5, false) }
        coVerify { profileManager.saveActiveProfileFilterUrls() }
        verify { ServiceController.requestRestart(any()) }
    }

    @Ignore("known bug: toggling a filter from its detail screen never reloads the engine")
    @Test
    fun `toggling reloads the filter engine`() = runTest {
        hot()
        vm.toggleFilter()
        coVerify { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `actions without a loaded filter do nothing`() = runTest {
        current.value = null
        hot()
        vm.toggleFilter()
        vm.updateFilter()
        vm.deleteFilter()
        vm.switchBuildMode()
        vm.openEditDialog()
        vm.saveEdit()
        coVerify(exactly = 0) { dao.setEnabled(any(), any()) }
        coVerify(exactly = 0) { dao.delete(any()) }
        assertFalse(vm.showEditDialog.value)
    }

    @Test
    fun `updating a built-in list downloads it and reloads`() = runTest {
        current.value = builtIn
        coEvery { repo.updateSingleFilter(builtIn) } returns Result.success(300)
        hot()
        vm.events.test {
            vm.updateFilter()
            assertEquals(UiEvent.ToastRes(R.string.filter_updated, listOf(300)), awaitItem())
        }
        coVerify { repo.loadAllEnabledFilters() }
        assertFalse(vm.isUpdating.value)
    }

    @Test
    fun `updating a custom list recompiles it and failures are reported`() = runTest {
        coEvery { customFilters.updateCustomFilter(custom) } returns Result.failure(RuntimeException("x"))
        hot()
        vm.events.test {
            vm.updateFilter()
            assertEquals(UiEvent.ToastRes(R.string.filter_update_failed), awaitItem())
        }
        coVerify(exactly = 0) { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `switching a server-built list to local enqueues a recompile`() = runTest {
        hot()
        vm.events.test {
            vm.switchBuildMode()
            assertEquals(UiEvent.ToastRes(R.string.filter_compile_enqueued), awaitItem())
        }
        verify { customFilters.enqueueRecompileLocally(custom) }
    }

    @Test
    fun `switching a local list to the server clears its binaries and recompiles`() = runTest {
        val local = custom.copy(trieUrl = "local://t", bloomUrl = "local://b")
        current.value = local
        coEvery { customFilters.updateCustomFilter(local.copy(trieUrl = "", bloomUrl = "")) } returns
            Result.success(local.copy(ruleCount = 99))
        hot()
        vm.events.test {
            vm.switchBuildMode()
            assertEquals(UiEvent.ToastRes(R.string.filter_updated, listOf(99)), awaitItem())
        }
        coVerify { repo.loadAllEnabledFilters() }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `a failed switch to the server is reported`() = runTest {
        current.value = custom.copy(trieUrl = "local://t")
        coEvery { customFilters.updateCustomFilter(any()) } returns Result.failure(RuntimeException("x"))
        hot()
        vm.events.test {
            vm.switchBuildMode()
            assertEquals(UiEvent.ToastRes(R.string.filter_update_failed), awaitItem())
        }
    }

    @Test
    fun `built-in lists cannot switch build mode, be deleted or edited`() = runTest {
        current.value = builtIn
        hot()
        vm.switchBuildMode()
        vm.deleteFilter()
        vm.openEditDialog()
        coVerify(exactly = 0) { customFilters.updateCustomFilter(any()) }
        coVerify(exactly = 0) { dao.delete(any()) }
        assertFalse(vm.showEditDialog.value)
    }

    @Test
    fun `deleting a custom list removes it, saves the profile and restarts`() = runTest {
        hot()
        vm.deleteFilter()
        coVerify { dao.delete(custom) }
        coVerify { profileManager.saveActiveProfileFilterUrls() }
        verify { ServiceController.requestRestart(any()) }
    }

    @Ignore("known bug: detail delete skips the binary cleanup in CustomFilterManager and the engine reload")
    @Test
    fun `deleting a custom list cleans up its binaries and reloads the engine`() = runTest {
        hot()
        vm.deleteFilter()
        coVerify { customFilters.deleteCustomFilter(custom) }
        coVerify { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `the edit dialog opens prefilled with the source url and closes`() = runTest {
        hot()
        vm.openEditDialog()
        assertTrue(vm.showEditDialog.value)
        assertEquals("Mine", vm.editName.value)
        assertEquals("https://src/list.txt", vm.editUrl.value)
        vm.closeEditDialog()
        assertFalse(vm.showEditDialog.value)
    }

    @Test
    fun `edit falls back to the compiled url when there is no source url`() = runTest {
        current.value = custom.copy(originalUrl = "")
        hot()
        vm.openEditDialog()
        assertEquals("https://compiled/5", vm.editUrl.value)
    }

    @Test
    fun `saving an edit validates blank name and url`() = runTest {
        hot()
        vm.openEditDialog()
        vm.setEditName("  ")
        vm.saveEdit()
        assertEquals("Name cannot be empty", vm.editError.value)
        vm.setEditName("n")
        vm.setEditUrl(" ")
        vm.saveEdit()
        assertEquals("Domain/URL cannot be empty", vm.editError.value)
        coVerify(exactly = 0) { customFilters.editCustomFilter(any(), any(), any()) }
    }

    @Test
    fun `renaming without a url change saves without reloading`() = runTest {
        coEvery { customFilters.editCustomFilter(custom, "Renamed", "https://src/list.txt") } returns
            Result.success(custom.copy(name = "Renamed"))
        hot()
        vm.openEditDialog()
        vm.setEditName(" Renamed ")
        vm.events.test {
            vm.saveEdit()
            assertEquals(UiEvent.ToastRes(R.string.filter_updated, listOf(10)), awaitItem())
        }
        assertFalse(vm.showEditDialog.value)
        assertFalse(vm.isSavingEdit.value)
        coVerify(exactly = 0) { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `changing the url reloads the engine and restarts`() = runTest {
        coEvery { customFilters.editCustomFilter(any(), any(), any()) } returns Result.success(custom)
        hot()
        vm.openEditDialog()
        vm.setEditUrl("https://new/list.txt")
        vm.saveEdit()
        coVerify { repo.loadAllEnabledFilters() }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `a failed edit keeps the dialog open with the error`() = runTest {
        coEvery { customFilters.editCustomFilter(any(), any(), any()) } returns Result.failure(RuntimeException("bad url"))
        hot()
        vm.openEditDialog()
        vm.saveEdit()
        assertEquals("bad url", vm.editError.value)
        assertTrue(vm.showEditDialog.value)
    }
}
