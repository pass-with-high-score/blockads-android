package app.pwhs.blockads.data.dao

import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.ElementRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ElementRuleDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ElementRuleDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = db.elementRuleDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `rules are grouped by domain, newest first`() = runTest {
        dao.insert(ElementRule(domain = "b.example", cssSelector = ".ad", createdAt = 1))
        dao.insert(ElementRule(domain = "a.example", cssSelector = ".old", createdAt = 1))
        dao.insert(ElementRule(domain = "a.example", cssSelector = ".new", createdAt = 2))

        assertEquals(listOf(".new", ".old"), dao.getRulesForDomain("a.example").first().map { it.cssSelector })
        assertEquals(
            listOf("a.example" to ".new", "a.example" to ".old", "b.example" to ".ad"),
            dao.getAllRules().first().map { it.domain to it.cssSelector },
        )
        assertEquals(setOf(".new", ".old"), dao.getSelectorsForDomain("a.example").toSet())
        assertEquals(3, dao.totalCount())
    }

    @Test
    fun `domain match is exact`() = runTest {
        dao.insert(ElementRule(domain = "example.com", cssSelector = ".x"))
        assertEquals(emptyList<String>(), dao.getSelectorsForDomain("sub.example.com"))
        assertEquals(emptyList<String>(), dao.getSelectorsForDomain("EXAMPLE.com"))
    }

    @Test
    fun `delete by id and by domain`() = runTest {
        dao.insert(ElementRule(id = 1, domain = "a.example", cssSelector = ".one"))
        dao.insert(ElementRule(id = 2, domain = "a.example", cssSelector = ".two"))
        dao.insert(ElementRule(id = 3, domain = "b.example", cssSelector = ".three"))

        dao.deleteById(1)
        assertEquals(listOf(".two"), dao.getSelectorsForDomain("a.example"))

        dao.deleteAllForDomain("a.example")
        assertEquals(1, dao.totalCount())
    }

    @Test
    fun `insert with an existing id replaces the rule`() = runTest {
        dao.insert(ElementRule(id = 7, domain = "a.example", cssSelector = ".before"))
        dao.insert(ElementRule(id = 7, domain = "a.example", cssSelector = ".after"))
        assertEquals(listOf(".after"), dao.getSelectorsForDomain("a.example"))
    }
}
