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

    @Query(
        """
        SELECT strftime('%Y-W%W', timestamp / 1000, 'unixepoch', 'localtime') AS week,
               COUNT(*) AS total,
               SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) AS blocked
        FROM dns_logs
        WHERE timestamp > :since
        GROUP BY week
        ORDER BY week ASC
    """
    )
    fun getWeeklyStats(
        since: Long = System.currentTimeMillis() - 28 * 86_400_000L
    ): Flow<List<WeeklyStat>>

    @Query(
        """
        SELECT strftime('%Y-%m', timestamp / 1000, 'unixepoch', 'localtime') AS month,
               COUNT(*) AS total,
               SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) AS blocked
        FROM dns_logs
        WHERE timestamp > :since
        GROUP BY month
        ORDER BY month ASC
    """
    )
    fun getMonthlyStats(
        since: Long = System.currentTimeMillis() - 365 * 86_400_000L
    ): Flow<List<MonthlyStat>>

    @Query(
        """
        SELECT appName, COUNT(*) AS total,
               SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) AS blocked
        FROM dns_logs
        WHERE appName != ''
        GROUP BY appName
        ORDER BY total DESC
        LIMIT :limit
    """
    )
    fun getTopApps(limit: Int = 15): Flow<List<AppStat>>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE timestamp > :since")
    fun getTotalCountSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM dns_logs WHERE isBlocked = 1 AND timestamp > :since")
    fun getBlockedCountSince(since: Long): Flow<Int>
}
