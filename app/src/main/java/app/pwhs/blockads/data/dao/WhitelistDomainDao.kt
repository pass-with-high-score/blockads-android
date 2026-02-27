package app.pwhs.blockads.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pwhs.blockads.data.entities.WhitelistDomain
import kotlinx.coroutines.flow.Flow

@Dao
interface WhitelistDomainDao {
    @Query("SELECT * FROM whitelist_domains ORDER BY addedTimestamp DESC")
    fun getAll(): Flow<List<WhitelistDomain>>

    @Query("SELECT domain FROM whitelist_domains")
    suspend fun getAllDomains(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(domain: WhitelistDomain)

    @Delete
    suspend fun delete(domain: WhitelistDomain)

    @Query("DELETE FROM whitelist_domains WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)

    @Query("SELECT COUNT(*) FROM whitelist_domains WHERE domain = :domain")
    suspend fun exists(domain: String): Int
}
