package app.pwhs.blockads.ui.dnsprovider.leak

import app.pwhs.blockads.data.remote.dnsleak.DnsLeakAnalyzeResponse
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakClientInfo
import app.pwhs.blockads.data.remote.dnsleak.DnsLeakDetectedResolver
import app.pwhs.blockads.data.repository.DnsLeakRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DnsLeakTestViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<DnsLeakRepository>()
    private lateinit var viewModel: DnsLeakTestViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = DnsLeakTestViewModel(
            dnsLeakRepository = repository,
            ioDispatcher = testDispatcher,
            latencyProvider = { 20L },
            dnssecProvider = { DnssecSecurityStatus(isValidating = true, algorithms = emptyList()) }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty and not loading`() {
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.result)
        assertNull(state.errorMessage)
    }

    @Test
    fun `startTest updates state to success on repository success`() = runTest(testDispatcher) {
        val fakeResponse = DnsLeakAnalyzeResponse(
            status = "ok",
            verdict = "secure",
            isLeak = false,
            hasEcsLeak = false,
            message = "Your DNS connection is private and secure.",
            client = DnsLeakClientInfo(ip = "1.2.3.4", asOrg = "ISP"),
            resolvers = listOf(
                DnsLeakDetectedResolver(ip = "1.1.1.1", org = "Cloudflare, Inc.")
            )
        )

        coEvery { repository.runLeakTest(any()) } returns Result.success(fakeResponse)

        viewModel.onIntent(DnsLeakTestUiIntent.StartTest)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.result)
        assertEquals("secure", state.result?.verdict)
        assertFalse(state.result?.isLeak ?: true)
        assertEquals(1, state.result?.resolvers?.size)
    }

    @Test
    fun `startTest updates state with error on repository failure`() = runTest(testDispatcher) {
        coEvery { repository.runLeakTest(any()) } returns Result.failure(RuntimeException("Network error"))

        viewModel.onIntent(DnsLeakTestUiIntent.StartTest)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.result)
        assertEquals("Network error", state.errorMessage)
    }

    @Test
    fun `reset clears all test state`() = runTest(testDispatcher) {
        val fakeResponse = DnsLeakAnalyzeResponse(status = "ok", verdict = "secure")
        coEvery { repository.runLeakTest(any()) } returns Result.success(fakeResponse)

        viewModel.onIntent(DnsLeakTestUiIntent.StartTest)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.result)

        viewModel.onIntent(DnsLeakTestUiIntent.Reset)
        val resetState = viewModel.uiState.value
        assertNull(resetState.result)
        assertFalse(resetState.isLoading)
    }

    @Test
    fun `startTest populates dnssecStatus on success`() = runTest(testDispatcher) {
        val fakeResponse = DnsLeakAnalyzeResponse(status = "ok", verdict = "secure")
        coEvery { repository.runLeakTest(any()) } returns Result.success(fakeResponse)

        viewModel.onIntent(DnsLeakTestUiIntent.StartTest)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.dnssecStatus)
    }
}
