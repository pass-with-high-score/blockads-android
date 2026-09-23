package app.pwhs.blockads.worker

import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.work.ListenableWorker.Result
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.repository.CustomFilterManager
import app.pwhs.blockads.data.repository.FilterListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class FilterUpdateWorkerTest {

    private var wifiOnly = false
    private var notificationMode = AppPreferences.NOTIFICATION_NORMAL
    private val prefs: AppPreferences = mockk {
        every { autoUpdateWifiOnly } answers { flowOf(wifiOnly) }
        every { autoUpdateNotification } answers { flowOf(notificationMode) }
    }
    private val repo: FilterListRepository = mockk {
        coEvery { forceUpdateAllEnabledFilters() } returns kotlin.Result.success(100)
        coEvery { loadAllEnabledFilters() } returns kotlin.Result.success(1)
    }
    private val custom1 = FilterList(id = 1, name = "c1", url = "https://c1")
    private val custom2 = FilterList(id = 2, name = "c2", url = "https://c2")
    private val disabled = FilterList(id = 3, name = "off", url = "https://off", isEnabled = false)
    private val dao: FilterListDao = mockk { coEvery { getAllNonBuiltIn() } returns listOf(custom1, disabled, custom2) }
    private val customManager: CustomFilterManager = mockk {
        coEvery { updateCustomFilter(custom1) } returns kotlin.Result.success(custom1.copy(ruleCount = 5))
        coEvery { updateCustomFilter(custom2) } returns kotlin.Result.failure(IllegalStateException("c2 down"))
    }
    private val h = WorkerHarness(module {
        single { prefs }
        single { repo }
        single { dao }
        single { customManager }
    })

    @After
    fun tearDown() = h.close()

    private fun setNetwork(vararg transports: Int) {
        val cm = h.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(caps).addTransportType(it) }
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun Notification.text() = extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()

    @Test
    fun `updates built-in and enabled custom filters, then reloads`() {
        assertEquals(Result.success(), h.run<FilterUpdateWorker>())
        coVerify(exactly = 0) { customManager.updateCustomFilter(disabled) }
        coVerify { repo.loadAllEnabledFilters() }
        assertTrue(h.notifications.single().text().contains("105"))
    }

    @Test
    fun `partial failure still succeeds`() {
        coEvery { repo.forceUpdateAllEnabledFilters() } returns kotlin.Result.failure(IllegalStateException("builtin down"))
        assertEquals(Result.success(), h.run<FilterUpdateWorker>())
        assertTrue(h.notifications.single().text().contains("5"))
    }

    @Test
    fun `total failure retries and reports the first error`() {
        coEvery { repo.forceUpdateAllEnabledFilters() } returns kotlin.Result.failure(IllegalStateException("builtin down"))
        coEvery { customManager.updateCustomFilter(custom1) } returns kotlin.Result.failure(IllegalStateException("c1 down"))

        assertEquals(Result.retry(), h.run<FilterUpdateWorker>())
        assertEquals("builtin down", h.notifications.single().text())
        coVerify { repo.loadAllEnabledFilters() }
    }

    @Test
    fun `silent mode posts a silent notification`() {
        notificationMode = AppPreferences.NOTIFICATION_SILENT
        h.run<FilterUpdateWorker>()
        @Suppress("DEPRECATION")
        assertEquals(NotificationCompat.PRIORITY_LOW, h.notifications.single().priority)
    }

    @Test
    fun `none mode posts nothing, even on failure`() {
        notificationMode = AppPreferences.NOTIFICATION_NONE
        assertEquals(Result.success(), h.run<FilterUpdateWorker>())
        coEvery { repo.forceUpdateAllEnabledFilters() } returns kotlin.Result.failure(IllegalStateException("x"))
        coEvery { dao.getAllNonBuiltIn() } returns emptyList()
        assertEquals(Result.retry(), h.run<FilterUpdateWorker>())
        assertTrue(h.notifications.isEmpty())
    }

    @Test
    fun `wifi-only waits for an unmetered network, including behind the VPN`() {
        wifiOnly = true
        setNetwork(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_CELLULAR)
        assertEquals(Result.retry(), h.run<FilterUpdateWorker>())
        coVerify(exactly = 0) { repo.forceUpdateAllEnabledFilters() }

        setNetwork(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(Result.success(), h.run<FilterUpdateWorker>())

        setNetwork(NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(Result.success(), h.run<FilterUpdateWorker>())
    }

    @Test
    fun `unexpected exceptions fail the work`() {
        coEvery { dao.getAllNonBuiltIn() } throws IllegalStateException("db closed")
        assertEquals(Result.failure(), h.run<FilterUpdateWorker>())
    }
}
