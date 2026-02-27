package app.pwhs.blockads.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.pwhs.blockads.data.entities.CustomDnsRule
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomDnsRuleDao {
    
    @Query("SELECT * FROM custom_dns_rules ORDER BY addedTimestamp DESC")
    fun getAllFlow(): Flow<List<CustomDnsRule>>
    
    @Query("SELECT * FROM custom_dns_rules ORDER BY addedTimestamp DESC")
    suspend fun getAll(): List<CustomDnsRule>
    
    @Query("SELECT * FROM custom_dns_rules WHERE isEnabled = 1 AND ruleType != 'COMMENT' ORDER BY addedTimestamp DESC")
    suspend fun getEnabledRules(): List<CustomDnsRule>
    
    @Query("SELECT domain FROM custom_dns_rules WHERE isEnabled = 1 AND ruleType = 'BLOCK'")
    suspend fun getBlockDomains(): List<String>
    
    @Query("SELECT domain FROM custom_dns_rules WHERE isEnabled = 1 AND ruleType = 'ALLOW'")
    suspend fun getAllowDomains(): List<String>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: CustomDnsRule): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<CustomDnsRule>)
    
    @Update
    suspend fun update(rule: CustomDnsRule)
    
    @Delete
    suspend fun delete(rule: CustomDnsRule)
    
    @Query("DELETE FROM custom_dns_rules")
    suspend fun deleteAll()

    @Query("DELETE FROM custom_dns_rules WHERE domain = :domain AND ruleType = 'BLOCK'")
    suspend fun deleteBlockRuleByDomain(domain: String)

    @Query("SELECT COUNT(*) FROM custom_dns_rules WHERE ruleType != 'COMMENT'")
    suspend fun getRuleCount(): Int
}
