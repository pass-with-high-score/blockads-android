/*
 * BlockAds - Ad blocker for Android using local VPN-based DNS filtering
 * Copyright (C) 2025 The BlockAds Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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

    @Query("""
        SELECT (timestamp / 3600000) * 3600000 AS hour,
               COUNT(*) AS total,
               SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) AS blocked
        FROM dns_logs
        WHERE timestamp > :since
        GROUP BY hour
        ORDER BY hour ASC
    """)
    fun getHourlyStats(since: Long = System.currentTimeMillis() - 86400000): Flow<List<HourlyStat>>
}
