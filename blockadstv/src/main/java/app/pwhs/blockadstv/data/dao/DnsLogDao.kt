package app.pwhs.blockadstv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pwhs.blockadstv.data.entities.AppStat
import app.pwhs.blockadstv.data.entities.BlockReasonRawStat
import app.pwhs.blockadstv.data.entities.CountryStat
import app.pwhs.blockadstv.data.entities.DailyStat
import app.pwhs.blockadstv.data.entities.DnsLogEntry
import app.pwhs.blockadstv.data.entities.HourlyStat
import app.pwhs.blockadstv.data.entities.MonthlyStat
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

    @Query("SELECT * FROM dns_logs WHERE isBlocked = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentBlockedLogs(limit: Int): Flow<List<DnsLogEntry>>

    @Query("SELECT * FROM dns_logs WHERE isBlocked = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentAllowedLogs(limit: Int): Flow<List<DnsLogEntry>>

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
        since: Long = System.currentTimeMillis() - 7 * 86_400_000L
    ): Flow<List<DailyStat>>

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
        SELECT appName, packageName,
               COUNT(*) AS totalQueries,
               SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) AS blockedQueries
        FROM dns_logs
        WHERE appName != ''
          AND (:since IS NULL OR timestamp > :since)
        GROUP BY appName, packageName
        """
    )
    fun getPerAppStats(since: Long? = null): Flow<List<AppStat>>

    @Query(
        """
        SELECT appName, packageName,
               COUNT(*) AS totalQueries,
               SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) AS blockedQueries
        FROM dns_logs
        WHERE appName != ''
        GROUP BY appName, packageName
        ORDER BY totalQueries DESC
        LIMIT :limit
        """
    )
    fun getTopApps(limit: Int = 15): Flow<List<AppStat>>

    @Query(
        """
        SELECT countryCode, COUNT(*) AS count
        FROM dns_logs
        WHERE countryCode != '' AND timestamp > :since
        GROUP BY countryCode
        ORDER BY count DESC
        """
    )
    fun getCountryStatsSince(since: Long): Flow<List<CountryStat>>

    @Query(
        """
        SELECT countryCode, COUNT(*) AS count
        FROM dns_logs
        WHERE countryCode != ''
        GROUP BY countryCode
        ORDER BY count DESC
        """
    )
    fun getAllCountryStats(): Flow<List<CountryStat>>

    @Query(
        """
        SELECT blockedBy, COUNT(*) AS count
        FROM dns_logs
        WHERE isBlocked = 1 AND blockedBy != '' AND timestamp > :since
        GROUP BY blockedBy
        ORDER BY count DESC
        """
    )
    fun getBlockReasonStatsSince(since: Long): Flow<List<BlockReasonRawStat>>

    @Query(
        """
        SELECT blockedBy, COUNT(*) AS count
        FROM dns_logs
        WHERE isBlocked = 1 AND blockedBy != ''
        GROUP BY blockedBy
        ORDER BY count DESC
        """
    )
    fun getAllBlockReasonStats(): Flow<List<BlockReasonRawStat>>
}
