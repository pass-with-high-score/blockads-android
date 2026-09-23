package app.pwhs.blockads.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.CustomDnsRule
import app.pwhs.blockads.data.entities.RuleType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomDnsRuleDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: CustomDnsRuleDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.customDnsRuleDao()
    }

    @After
    fun tearDown() = db.close()

    private fun rule(text: String, type: RuleType, domain: String, enabled: Boolean = true, at: Long = 0) =
        CustomDnsRule(rule = text, ruleType = type, domain = domain, isEnabled = enabled, addedTimestamp = at)

    @Test
    fun blockAndAllowDomainsSkipDisabledRulesAndComments() = runTest {
        dao.insertAll(
            listOf(
                rule("||ads.example^", RuleType.BLOCK, "ads.example"),
                rule("||off.example^", RuleType.BLOCK, "off.example", enabled = false),
                rule("@@||ok.example^", RuleType.ALLOW, "ok.example"),
                rule("! note", RuleType.COMMENT, ""),
            )
        )

        assertEquals(listOf("ads.example"), dao.getBlockDomains())
        assertEquals(listOf("ok.example"), dao.getAllowDomains())
        assertEquals(3, dao.getRuleCount())
        assertEquals(2, dao.getEnabledRules().size)
    }

    @Test
    fun existsMatchesExactRuleTextOnly() = runTest {
        dao.insert(rule("||ads.example^", RuleType.BLOCK, "ads.example"))

        assertEquals(1, dao.exists("||ads.example^"))
        assertEquals(0, dao.exists("ads.example"))
    }

    @Test
    fun flowEmitsNewestFirstOnInsert() = runTest {
        dao.getAllFlow().test {
            assertEquals(emptyList<CustomDnsRule>(), awaitItem())

            dao.insert(rule("||old.example^", RuleType.BLOCK, "old.example", at = 1))
            assertEquals(listOf("old.example"), awaitItem().map { it.domain })

            dao.insert(rule("||new.example^", RuleType.BLOCK, "new.example", at = 2))
            assertEquals(listOf("new.example", "old.example"), awaitItem().map { it.domain })
        }
    }

    @Test
    fun deleteBlockRuleByDomainLeavesAllowRule() = runTest {
        dao.insertAll(
            listOf(
                rule("||x.example^", RuleType.BLOCK, "x.example"),
                rule("@@||x.example^", RuleType.ALLOW, "x.example"),
            )
        )

        dao.deleteBlockRuleByDomain("x.example")

        assertEquals(listOf(RuleType.ALLOW), dao.getAll().map { it.ruleType })
    }
}
