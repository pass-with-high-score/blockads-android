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
}
