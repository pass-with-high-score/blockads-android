package app.pwhs.blockads.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DnsLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DnsLogEntry)

    @Query("SELECT * FROM dns_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<DnsLogEntry>>

    @Query("SELECT * FROM dns_logs WHERE isBlocked = 1 ORDER BY timestamp DESC")
    fun getBlockedOnly(): Flow<List<DnsLogEntry>>

    @Query("SELECT * FROM dns_logs WHERE timestamp > :since ORDER BY timestamp DESC")
    fun getAllSince(since: Long): Flow<List<DnsLogEntry>>

    @Query("SELECT * FROM dns_logs WHERE isBlocked = 1 AND timestamp > :since ORDER BY timestamp DESC")
    fun getBlockedOnlySince(since: Long): Flow<List<DnsLogEntry>>

    @Query("SELECT DISTINCT appName FROM dns_logs WHERE appName != '' ORDER BY appName ASC")
    fun getDistinctAppNames(): Flow<List<String>>

    @Query("SELECT * FROM dns_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int): Flow<List<DnsLogEntry>>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1")
    fun getBlockedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dns_logs")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END), 0) AS blocked, COUNT(*) AS total FROM dns_logs")
    suspend fun getWidgetStats(): WidgetStats

    @Query("DELETE FROM dns_logs")
    suspend fun clearAll()

    @Query("SELECT * FROM dns_logs WHERE isBlocked = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentBlocked(limit: Int = 5): Flow<List<DnsLogEntry>>

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
        SELECT (timestamp / 86400000) * 86400000 AS day,
               COUNT(*) AS total,
               SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) AS blocked
        FROM dns_logs
        WHERE timestamp > :since
        GROUP BY day
        ORDER BY day ASC
    """
    )
    fun getDailyStats(
        since: Long = System.currentTimeMillis() - 7 * 86_400_000L // 7 days in ms
    ): Flow<List<DailyStat>>

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
}
