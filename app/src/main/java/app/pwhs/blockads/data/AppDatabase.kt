package app.pwhs.blockads.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DnsLogEntry::class, FilterList::class, WhitelistDomain::class, DnsErrorEntry::class, CustomDnsRule::class, FirewallRule::class],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dnsLogDao(): DnsLogDao
    abstract fun filterListDao(): FilterListDao
    abstract fun whitelistDomainDao(): WhitelistDomainDao
    abstract fun dnsErrorDao(): DnsErrorDao
    abstract fun customDnsRuleDao(): CustomDnsRuleDao
    abstract fun firewallRuleDao(): FirewallRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `filter_lists` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL DEFAULT 1,
                        `domainCount` INTEGER NOT NULL DEFAULT 0,
                        `lastUpdated` INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `whitelist_domains` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `domain` TEXT NOT NULL,
                        `addedTimestamp` INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filter_lists ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE filter_lists ADD COLUMN isBuiltIn INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `dns_errors` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `domain` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `error_type` TEXT NOT NULL,
                        `error_message` TEXT NOT NULL,
                        `upstream_dns` TEXT NOT NULL,
                        `attempted_fallback` INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dns_logs ADD COLUMN appName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `custom_dns_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `rule` TEXT NOT NULL,
                        `ruleType` TEXT NOT NULL,
                        `domain` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL DEFAULT 1,
                        `addedTimestamp` INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dns_logs_timestamp` ON `dns_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dns_logs_isBlocked_domain` ON `dns_logs` (`isBlocked`, `domain`)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dns_logs ADD COLUMN resolvedIp TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dns_logs ADD COLUMN blockedBy TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dns_logs_appName` ON `dns_logs` (`appName`)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filter_lists ADD COLUMN category TEXT NOT NULL DEFAULT 'AD'")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `firewall_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `blockWifi` INTEGER NOT NULL DEFAULT 1,
                        `blockMobileData` INTEGER NOT NULL DEFAULT 1,
                        `scheduleEnabled` INTEGER NOT NULL DEFAULT 0,
                        `scheduleStartHour` INTEGER NOT NULL DEFAULT 22,
                        `scheduleStartMinute` INTEGER NOT NULL DEFAULT 0,
                        `scheduleEndHour` INTEGER NOT NULL DEFAULT 6,
                        `scheduleEndMinute` INTEGER NOT NULL DEFAULT 0,
                        `isEnabled` INTEGER NOT NULL DEFAULT 1
                    )"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_firewall_rules_packageName` ON `firewall_rules` (`packageName`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "blockads_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

