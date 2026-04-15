package app.pwhs.blockadstv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.pwhs.blockadstv.data.dao.CustomDnsRuleDao
import app.pwhs.blockadstv.data.dao.DnsLogDao
import app.pwhs.blockadstv.data.dao.FilterListDao
import app.pwhs.blockadstv.data.dao.WhitelistDomainDao
import app.pwhs.blockadstv.data.entities.CustomDnsRule
import app.pwhs.blockadstv.data.entities.DnsLogEntry
import app.pwhs.blockadstv.data.entities.FilterList
import app.pwhs.blockadstv.data.entities.WhitelistDomain

@Database(
    entities = [
        DnsLogEntry::class,
        FilterList::class,
        WhitelistDomain::class,
        CustomDnsRule::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class TvDatabase : RoomDatabase() {

    abstract fun dnsLogDao(): DnsLogDao
    abstract fun filterListDao(): FilterListDao
    abstract fun whitelistDomainDao(): WhitelistDomainDao
    abstract fun customDnsRuleDao(): CustomDnsRuleDao

    companion object {
        @Volatile
        private var INSTANCE: TvDatabase? = null

        fun getInstance(context: Context): TvDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        @Suppress("DEPRECATION")
        private fun buildDatabase(context: Context): TvDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                TvDatabase::class.java,
                "blockadstv_database",
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
