package app.pwhs.blockadstv.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pwhs.blockadstv.data.entities.CustomDnsRule
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomDnsRuleDao {
    @Query("SELECT * FROM custom_dns_rules ORDER BY addedTimestamp DESC")
    fun getAllFlow(): Flow<List<CustomDnsRule>>

    @Query("SELECT domain FROM custom_dns_rules WHERE isEnabled = 1 AND ruleType = 'BLOCK'")
    suspend fun getBlockDomains(): List<String>

    @Query("SELECT domain FROM custom_dns_rules WHERE isEnabled = 1 AND ruleType = 'ALLOW'")
    suspend fun getAllowDomains(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: CustomDnsRule): Long

    @Delete
    suspend fun delete(rule: CustomDnsRule)

    @Query("SELECT COUNT(*) FROM custom_dns_rules WHERE domain = :domain AND ruleType = :ruleType")
    suspend fun exists(domain: String, ruleType: String): Int
}
