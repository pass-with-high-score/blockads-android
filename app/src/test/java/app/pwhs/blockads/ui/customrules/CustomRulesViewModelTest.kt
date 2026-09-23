package app.pwhs.blockads.ui.customrules

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.ServiceController
import app.pwhs.blockads.ui.FakeCustomDnsRuleDao
import app.pwhs.blockads.ui.MainDispatcherRule
import app.pwhs.blockads.ui.customrules.data.ExportFormat
import app.pwhs.blockads.ui.settle
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CustomRulesViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dao = FakeCustomDnsRuleDao()
    private val repo: FilterListRepository = mockk(relaxed = true)
    private lateinit var vm: CustomRulesViewModel

    private var successCount: Int? = null
    private var error: String? = null
    private val onSuccess: (Int) -> Unit = { successCount = it }
    private val onError: (String) -> Unit = { error = it }

    @Before
    fun setUp() {
        mockkObject(ServiceController)
        every { ServiceController.requestRestart(any()) } just Runs
        vm = CustomRulesViewModel(dao, repo, app)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun rule(text: String, type: RuleType, domain: String) =
        CustomDnsRule(rule = text, ruleType = type, domain = domain)

    private fun fileUri(content: String): Uri {
        val file = tempFolder.newFile()
        file.writeText(content)
        return Uri.fromFile(file)
    }

    @Test
    fun `rules state mirrors the table`() {
        dao.rules.value = listOf(rule("||a.com^", RuleType.BLOCK, "a.com"))
        assertEquals(dao.rules.value, vm.rules.value)
    }

    @Test
    fun `adding a valid rule stores it, reloads rules and restarts`() {
        var ok = false
        vm.addRule("||ads.example^", onSuccess = { ok = true })

        assertTrue(ok)
        assertEquals(listOf("ads.example"), dao.rules.value.map { it.domain })
        coVerify { repo.loadCustomRules() }
        verify { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `adding an invalid rule reports the format error`() {
        vm.addRule("not a domain!", onError = onError)
        assertEquals("Invalid rule format", error)
        assertTrue(dao.rules.value.isEmpty())
    }

    @Test
    fun `a rule with the same type and domain is a duplicate even with different text`() {
        dao.rules.value = listOf(rule("||ads.example^", RuleType.BLOCK, "ads.example"))
        vm.addRule("ads.example", onError = onError)
        assertEquals("Rule already exists", error)
        assertEquals(1, dao.rules.value.size)
    }

    @Test
    fun `allow and block rules for the same domain are not duplicates`() {
        dao.rules.value = listOf(rule("||ads.example^", RuleType.BLOCK, "ads.example"))
        var ok = false
        vm.addRule("@@||ads.example^", onSuccess = { ok = true })
        assertTrue(ok)
        assertEquals(2, dao.rules.value.size)
    }

    @Test
    fun `comments dedupe on their exact text`() {
        dao.rules.value = listOf(rule("! note", RuleType.COMMENT, ""))
        vm.addRule("! note", onError = onError)
        assertEquals("Rule already exists", error)

        var ok = false
        vm.addRule("! other note", onSuccess = { ok = true })
        assertTrue(ok)
    }

    @Test
    fun `a DAO failure while adding surfaces its message`() {
        val failing = mockk<app.pwhs.blockads.data.dao.CustomDnsRuleDao>(relaxed = true)
        coEvery { failing.getAll() } throws IllegalStateException("disk full")
        CustomRulesViewModel(failing, repo, app).addRule("a.com", onError = onError)
        assertEquals("disk full", error)
    }

    @Test
    fun `bulk add skips duplicates against the table and within the batch`() {
        dao.rules.value = listOf(rule("||a.com^", RuleType.BLOCK, "a.com"))
        vm.addRules("a.com\nb.com\n||b.com^\n! c\n! c\n@@a.com\ninvalid!", onSuccess, onError)

        assertEquals(3, successCount)
        assertEquals(
            listOf("a.com" to RuleType.BLOCK, "b.com" to RuleType.BLOCK, "" to RuleType.COMMENT, "a.com" to RuleType.ALLOW),
            dao.rules.value.map { it.domain to it.ruleType },
        )
    }

    @Test
    fun `bulk add of only known rules reports they already exist`() {
        dao.rules.value = listOf(rule("||a.com^", RuleType.BLOCK, "a.com"))
        vm.addRules("a.com", onSuccess, onError)
        assertEquals("Rules already exist", error)
    }

    @Test
    fun `bulk add without any parsable rule reports none found`() {
        vm.importRules("!!!bad\n  \n#x", onSuccess, onError)
        assertEquals("'!' lines are comments, '#x' is dropped", 1, successCount)

        error = null
        vm.addRules("not valid!\n\n", onSuccess, onError)
        assertEquals("No valid rules found", error)
    }

    @Test
    fun `delete, toggle and delete-all each reload rules and restart`() {
        val a = rule("||a.com^", RuleType.BLOCK, "a.com").copy(id = 1)
        val b = rule("||b.com^", RuleType.BLOCK, "b.com").copy(id = 2)
        dao.rules.value = listOf(a, b)

        vm.toggleRule(a)
        assertEquals(false, dao.rules.value.first { it.id == 1 }.isEnabled)
        vm.deleteRule(b)
        assertEquals(listOf(1), dao.rules.value.map { it.id })
        vm.deleteAllRules()
        assertTrue(dao.rules.value.isEmpty())

        coVerify(exactly = 3) { repo.loadCustomRules() }
        verify(exactly = 3) { ServiceController.requestRestart(any()) }
    }

    @Test
    fun `a failed reload is surfaced as an error and can be cleared`() {
        coEvery { repo.loadCustomRules() } throws IllegalStateException("engine gone")
        vm.deleteAllRules()
        assertEquals("Failed to reload filters: engine gone", vm.error.value)
        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun `exportRules joins the raw rule text`() {
        dao.rules.value = listOf(rule("||a.com^", RuleType.BLOCK, "a.com"), rule("! hi", RuleType.COMMENT, ""))
        assertEquals("||a.com^\n! hi", vm.exportRules())
    }

    @Test
    fun `exporting with no rules reports it`() {
        vm.settle { exportRulesToUri(fileUri(""), ExportFormat.TXT, onSuccess, onError) }
        assertEquals("No rules to export", error)
    }

    @Test
    fun `TXT export then import round trips into an empty table`() {
        dao.rules.value = listOf(
            rule("||a.com^", RuleType.BLOCK, "a.com").copy(id = 1),
            rule("@@||b.com^", RuleType.ALLOW, "b.com").copy(id = 2),
        )
        val out = tempFolder.newFile()
        vm.settle { exportRulesToUri(Uri.fromFile(out), ExportFormat.TXT, onSuccess, onError) }
        assertEquals(2, successCount)
        assertEquals("||a.com^\n@@||b.com^", out.readText())

        dao.rules.value = emptyList()
        successCount = null
        vm.settle { importRulesFromUri(Uri.fromFile(out), onSuccess, onError) }
        assertEquals(2, successCount)
        assertEquals(listOf(RuleType.BLOCK, RuleType.ALLOW), dao.rules.value.map { it.ruleType })
    }

    @Test
    fun `JSON export then import round trips type, domain and enabled state`() {
        dao.rules.value = listOf(
            rule("||a.com^", RuleType.BLOCK, "a.com").copy(id = 1, isEnabled = false),
            rule("! note", RuleType.COMMENT, "").copy(id = 2),
        )
        val out = tempFolder.newFile()
        vm.settle { exportRulesToUri(Uri.fromFile(out), ExportFormat.JSON, onSuccess, onError) }
        assertTrue(out.readText().contains("\"ruleType\": \"BLOCK\""))

        dao.rules.value = emptyList()
        vm.settle { importRulesFromUri(Uri.fromFile(out), onSuccess, onError) }
        assertEquals(
            listOf(Triple("a.com", RuleType.BLOCK, false), Triple("", RuleType.COMMENT, true)),
            dao.rules.value.map { Triple(it.domain, it.ruleType, it.isEnabled) },
        )
    }

    @Test
    fun `importing an existing set reports that the rules already exist`() {
        dao.rules.value = listOf(rule("||a.com^", RuleType.BLOCK, "a.com"))
        vm.settle { importRulesFromUri(fileUri("||a.com^"), onSuccess, onError) }
        assertEquals("Rules already exist", error)
    }

    @Test
    fun `importing an empty file or one with no rules is rejected`() {
        vm.settle { importRulesFromUri(fileUri("   \n"), onSuccess, onError) }
        assertEquals("File is empty", error)

        vm.settle { importRulesFromUri(fileUri("not valid!"), onSuccess, onError) }
        assertEquals("No valid rules found in file", error)
    }

    @Test
    fun `importing from an unreadable uri reports the failure`() {
        vm.settle { importRulesFromUri(Uri.fromFile(File(tempFolder.root, "missing.txt")), onSuccess, onError) }
        assertTrue(error!!.isNotBlank())
        assertNull(successCount)
    }

    @Test
    fun `JSON import maps an unknown ruleType to BLOCK`() {
        val json = """[{"rule":"||x.com^","ruleType":"NOPE","domain":"x.com","isEnabled":true}]"""
        vm.settle { importRulesFromUri(fileUri(json), onSuccess, onError) }
        assertEquals(listOf(RuleType.BLOCK), dao.rules.value.map { it.ruleType })
    }

    @Ignore("known bug: JSON import bypasses CustomRuleParser, so malformed domains enter the rule table")
    @Test
    fun `JSON import rejects entries whose rule does not parse`() {
        val json = """[{"rule":"not a rule!","ruleType":"BLOCK","domain":"not a domain!","isEnabled":true}]"""
        vm.settle { importRulesFromUri(fileUri(json), onSuccess, onError) }
        assertTrue(dao.rules.value.isEmpty())
    }
}
