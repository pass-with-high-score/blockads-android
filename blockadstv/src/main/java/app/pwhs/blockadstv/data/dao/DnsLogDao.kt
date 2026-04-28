package app.pwhs.blockadstv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pwhs.blockadstv.data.entities.DnsLogEntry
import app.pwhs.blockadstv.data.entities.HourlyStat
import app.pwhs.blockadstv.data.entities.TopBlockedDomain
import kotlinx.coroutines.flow.Flow

@Dao
interface DnsLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DnsLogEntry)

    @Query("SELECT * FROM dns_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<DnsLogEntry>>

    @Query("SELECT * FROM dns_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int): Flow<List<DnsLogEntry>>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1")
    fun getBlockedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dns_logs")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE timestamp > :since")
    fun getTotalCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1 AND timestamp > :since")
    fun getBlockedCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1 AND timestamp > :since")
    suspend fun getBlockedCountSinceSync(since: Long): Int

    @Query("SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1")
    suspend fun getBlockedCountSync(): Int

    @Query(
        """
        SELECT (timestamp / 3600000) * 3600000 AS hour,
               COUNT(*) AS total,
               SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) AS blocked
        FROM dns_logs
        WHERE timestamp > :since
        GROUP BY hour
        ORDER BY hour ASC
    """
    )
    fun getHourlyStats(since: Long = System.currentTimeMillis() - 86400000): Flow<List<HourlyStat>>

    @Query(
        """
        SELECT domain, COUNT(*) AS count
        FROM dns_logs
        WHERE isBlocked = 1
        GROUP BY domain
        ORDER BY count DESC
        LIMIT :limit
    """
    )
    fun getTopBlockedDomains(limit: Int = 10): Flow<List<TopBlockedDomain>>

    @Query("SELECT * FROM dns_logs WHERE isBlocked = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentBlocked(limit: Int = 5): Flow<List<DnsLogEntry>>

    @Query("DELETE FROM dns_logs")
    suspend fun clearAll()

    @Query(
        """
        SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1 AND timestamp > :since
        AND blockedBy = 'SECURITY'
        """
    )
    fun getSecurityBlockedCountSince(since: Long): Flow<Int>
}
