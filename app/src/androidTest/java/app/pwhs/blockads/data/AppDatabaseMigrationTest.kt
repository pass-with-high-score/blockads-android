package app.pwhs.blockads.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Only 1.json, 4.json and the current schema are exported, so the chain is tested from those starting points.
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migratesFromVersion1ToLatestKeepingDnsLogs() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO dns_logs (domain, timestamp, isBlocked, queryType, responseTimeMs) " +
                    "VALUES ('ads.example', 1000, 1, 'A', 12)"
            )
        }

        helper.runMigrationsAndValidate(DB, LATEST, true, *AppDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT domain, isBlocked, appName, blockedBy FROM dns_logs").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("ads.example", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals("", c.getString(2))
                assertEquals("", c.getString(3))
            }
        }
    }

    @Test
    fun migratesFromVersion4ToLatestKeepingFiltersAndWhitelist() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO filter_lists (name, url, description, isEnabled, isBuiltIn, domainCount, lastUpdated) " +
                    "VALUES ('My list', 'https://lists.example/hosts', '', 1, 0, 42, 5)"
            )
            db.execSQL("INSERT INTO whitelist_domains (domain, addedTimestamp) VALUES ('ok.example', 7)")
        }

        helper.runMigrationsAndValidate(DB, LATEST, true, *AppDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT name, url, isEnabled, domainCount FROM filter_lists WHERE url = 'https://lists.example/hosts'").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("My list", c.getString(0))
                assertEquals(1, c.getInt(2))
                assertEquals(42, c.getInt(3))
            }
            db.query("SELECT domain FROM whitelist_domains").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("ok.example", c.getString(0))
            }
        }
    }

    private companion object {
        const val DB = "migration-test"
        const val LATEST = 14
    }
}
