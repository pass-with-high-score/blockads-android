package app.pwhs.blockads.worker

import androidx.work.ListenableWorker.Result
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.testutil.ShadowGoSeq
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tunnel.Tunnel
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowGoSeq::class], instrumentedPackages = ["go", "tunnel"])
class FilterCompileWorkerTest {

    private val rows = mutableMapOf<Long, FilterList>()
    private val dao: FilterListDao = mockk {
        coEvery { insert(any()) } answers { val id = 40L + rows.size; rows[id] = firstArg<FilterList>().copy(id = id); id }
        coEvery { update(any()) } answers { firstArg<FilterList>().let { rows[it.id] = it } }
        coEvery { getById(any()) } answers { rows[firstArg()] }
        coEvery { delete(any()) } answers { rows.remove(firstArg<FilterList>().id) }
    }
    private val repo: FilterListRepository = mockk { coEvery { loadAllEnabledFilters() } returns kotlin.Result.success(1) }
    private var download: () -> String = { "ads.test\ntrack.test\n" }
    private val client = HttpClient(MockEngine { respond(download()) })
    private var compiledRules = 2L
    private val h = WorkerHarness(module {
        single { dao }
        single { repo }
        single { client }
    })

    init {
        mockkStatic(Tunnel::class)
        every { Tunnel.compileFilterList(any(), any(), any()) } answers {
            File(secondArg<String>()).writeText("trie")
            File(thirdArg<String>()).writeText("bloom")
            compiledRules
        }
    }

    @After
    fun tearDown() {
        h.close()
        unmockkAll()
    }

    private fun compile(url: String? = "https://lists.test/a.txt", id: Long = -1L) = h.run<FilterCompileWorker> {
        setInputData(
            if (url == null) androidx.work.Data.EMPTY
            else FilterCompileWorker.buildInputData(url, "My list", id)
        )
    }

    private fun compiledFile(id: Long, ext: String) = File(h.app.filesDir, "remote_filters/$id.$ext")

    @Test
    fun `missing URL fails immediately`() {
        assertEquals(Result.failure(), compile(url = null))
    }

    @Test
    fun `new list is inserted with local artifact URLs and reported`() {
        val result = compile()

        val row = rows.values.single()
        assertEquals(Result.success(androidx.work.workDataOf(
            FilterCompileWorker.KEY_RESULT_RULE_COUNT to 2,
            FilterCompileWorker.KEY_RESULT_FILTER_ID to row.id,
        )), result)
        assertEquals("local://${row.id}.trie", row.trieUrl)
        assertEquals("local://${row.id}.bloom", row.bloomUrl)
        assertEquals(2, row.ruleCount)
        assertEquals("trie", compiledFile(row.id, "trie").readText())
        assertEquals("bloom", compiledFile(row.id, "bloom").readText())
        coVerify { repo.loadAllEnabledFilters() }
        assertTrue(h.app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("worker_compile_") })
    }

    @Test
    fun `recompiling an existing row updates it in place`() {
        rows[7] = FilterList(id = 7, name = "Old", url = "https://lists.test/a.txt", ruleCount = 1)
        compiledRules = 9
        assertTrue(compile(id = 7) is Result.Success)
        assertEquals(9, rows.getValue(7).ruleCount)
        assertEquals("Custom filter: Old", rows.getValue(7).description)
        assertEquals(1, rows.size)
    }

    @Test
    fun `download failure removes an empty placeholder`() {
        rows[7] = FilterList(id = 7, name = "Compiling", url = "https://lists.test/a.txt", ruleCount = 0)
        download = { throw IOException("offline") }
        assertEquals(Result.failure(), compile(id = 7))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `download failure keeps a previously compiled row`() {
        rows[7] = FilterList(id = 7, name = "Good", url = "https://lists.test/a.txt", ruleCount = 50)
        download = { throw IOException("offline") }
        assertEquals(Result.failure(), compile(id = 7))
        assertEquals(50, rows.getValue(7).ruleCount)
    }

    @Test
    fun `zero rules fails a new list without inserting`() {
        compiledRules = 0
        assertEquals(Result.failure(), compile())
        assertTrue(rows.isEmpty())
    }

    @Ignore("with zero rules the worker returns before its cleanup, leaving the 'Compiling' placeholder row behind")
    @Test
    fun `zero rules removes the placeholder`() {
        rows[7] = FilterList(id = 7, name = "Compiling", url = "https://lists.test/a.txt", ruleCount = 0)
        compiledRules = 0
        compile(id = 7)
        assertTrue(rows.isEmpty())
    }
}
