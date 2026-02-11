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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterListDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(filterList: FilterList): Long

    @Update
    suspend fun update(filterList: FilterList)

    @Delete
    suspend fun delete(filterList: FilterList)

    @Query("SELECT * FROM filter_lists ORDER BY name ASC")
    fun getAll(): Flow<List<FilterList>>

    @Query("SELECT * FROM filter_lists WHERE isEnabled = 1")
    suspend fun getEnabled(): List<FilterList>

    @Query("SELECT COUNT(*) FROM filter_lists")
    suspend fun count(): Int

    @Query("UPDATE filter_lists SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE filter_lists SET domainCount = :count, lastUpdated = :timestamp WHERE id = :id")
    suspend fun updateStats(id: Long, count: Int, timestamp: Long)

    @Query("SELECT * FROM filter_lists WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): FilterList?
}
