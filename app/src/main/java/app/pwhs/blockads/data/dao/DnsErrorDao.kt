package app.pwhs.blockads.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.pwhs.blockads.data.entities.DnsErrorEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface DnsErrorDao {
    @Insert
    suspend fun insert(error: DnsErrorEntry)

    @Query("SELECT * FROM dns_errors ORDER BY timestamp DESC LIMIT 100")
    fun getAllErrors(): Flow<List<DnsErrorEntry>>

    @Query("SELECT COUNT(*) FROM dns_errors WHERE timestamp > :since")
    fun getErrorCountSince(since: Long): Flow<Int>

    @Query("DELETE FROM dns_errors")
    suspend fun deleteAll()

    @Query("DELETE FROM dns_errors WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
