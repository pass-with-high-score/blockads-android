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
