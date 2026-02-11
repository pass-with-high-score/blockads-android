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
import androidx.room.Query
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
