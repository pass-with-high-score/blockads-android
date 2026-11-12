package app.pwhs.blockads.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.pwhs.blockads.data.entities.FirewallRule
import kotlinx.coroutines.flow.Flow

@Dao
interface FirewallRuleDao {

    @Query("SELECT * FROM firewall_rules ORDER BY packageName ASC")
    fun getAll(): Flow<List<FirewallRule>>

    @Query("SELECT * FROM firewall_rules WHERE isEnabled = 1")
    suspend fun getEnabledRules(): List<FirewallRule>

    /** All rules: isEnabled = 1 means blocked, isEnabled = 0 means explicitly allowed. */
    @Query("SELECT * FROM firewall_rules")
    suspend fun getAllSnapshot(): List<FirewallRule>

    @Query("SELECT * FROM firewall_rules WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): FirewallRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: FirewallRule)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<FirewallRule>)

    @Query("DELETE FROM firewall_rules WHERE packageName IN (:packageNames)")
    suspend fun deleteByPackageNames(packageNames: List<String>)

    @Update
    suspend fun update(rule: FirewallRule)

    @Delete
    suspend fun delete(rule: FirewallRule)

    @Query("DELETE FROM firewall_rules WHERE packageName = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    @Query("SELECT COUNT(*) FROM firewall_rules WHERE isEnabled = 1")
    fun getEnabledCount(): Flow<Int>
}
