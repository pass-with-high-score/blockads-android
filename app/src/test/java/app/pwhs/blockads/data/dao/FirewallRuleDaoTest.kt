package app.pwhs.blockads.data.dao

import app.cash.turbine.test
import app.pwhs.blockads.data.AppDatabase
import app.pwhs.blockads.data.entities.FirewallRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirewallRuleDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FirewallRuleDao

    @Before
    fun setUp() {
        db = inMemoryDb()
        dao = db.firewallRuleDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `insert replaces a rule with the same package and churns its id`() = runTest {
        dao.insert(FirewallRule(packageName = "com.app", blockWifi = true))
        val firstId = dao.getByPackageName("com.app")!!.id

        dao.insert(FirewallRule(packageName = "com.app", blockWifi = false))

        val rows = dao.getAll().first()
        assertEquals(1, rows.size)
        assertEquals(false, rows.single().blockWifi)
        assertNotEquals(firstId, rows.single().id)
    }

    @Test
    fun `insertAll ignores packages that already have a rule`() = runTest {
        dao.insert(FirewallRule(packageName = "com.kept", blockMobileData = false))

        dao.insertAll(
            listOf(
                FirewallRule(packageName = "com.kept", blockMobileData = true),
                FirewallRule(packageName = "com.new"),
            )
        )

        assertEquals(listOf("com.kept", "com.new"), dao.getAll().first().map { it.packageName })
        assertEquals(false, dao.getByPackageName("com.kept")!!.blockMobileData)
    }

    @Test
    fun `enabled rules and count`() = runTest {
        dao.getEnabledCount().test {
            assertEquals(0, awaitItem())
            dao.insertAll(
                listOf(
                    FirewallRule(packageName = "a"),
                    FirewallRule(packageName = "b", isEnabled = false),
                    FirewallRule(packageName = "c"),
                )
            )
            assertEquals(2, awaitItem())
        }
        assertEquals(setOf("a", "c"), dao.getEnabledRules().map { it.packageName }.toSet())
    }

    @Test
    fun `update and the delete variants`() = runTest {
        dao.insertAll(listOf("a", "b", "c", "d").map { FirewallRule(packageName = it) })

        val a = dao.getByPackageName("a")!!
        dao.update(a.copy(scheduleEnabled = true, scheduleStartHour = 1))
        assertEquals(1, dao.getByPackageName("a")!!.scheduleStartHour)

        dao.delete(dao.getByPackageName("a")!!)
        dao.deleteByPackageName("b")
        dao.deleteByPackageNames(listOf("c", "missing"))

        assertNull(dao.getByPackageName("a"))
        assertEquals(listOf("d"), dao.getAll().first().map { it.packageName })
    }

    @Test
    fun `defaults block both networks with a 22-6 schedule off`() {
        val rule = FirewallRule(packageName = "x")
        assertEquals(listOf(true, true, false, 22, 0, 6, 0, true),
            listOf(rule.blockWifi, rule.blockMobileData, rule.scheduleEnabled, rule.scheduleStartHour,
                rule.scheduleStartMinute, rule.scheduleEndHour, rule.scheduleEndMinute, rule.isEnabled))
    }
}
