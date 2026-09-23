package app.pwhs.blockads.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pwhs.blockads.data.entities.ElementRule
import kotlinx.coroutines.flow.Flow

@Dao
interface ElementRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: ElementRule)

    @Query("SELECT * FROM element_rules WHERE domain = :domain ORDER BY createdAt DESC")
    fun getRulesForDomain(domain: String): Flow<List<ElementRule>>

    @Query("SELECT * FROM element_rules ORDER BY domain ASC, createdAt DESC")
    fun getAllRules(): Flow<List<ElementRule>>

    @Query("SELECT cssSelector FROM element_rules WHERE domain = :domain")
    suspend fun getSelectorsForDomain(domain: String): List<String>

    @Query("DELETE FROM element_rules WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM element_rules WHERE domain = :domain")
    suspend fun deleteAllForDomain(domain: String)

    @Query("SELECT COUNT(*) FROM element_rules")
    suspend fun totalCount(): Int
}
