package app.pwhs.blockads.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.pwhs.blockads.data.entities.ConfigProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Query("SELECT * FROM configs ORDER BY isBuiltIn DESC, createdAt ASC")
    fun getAllFlow(): Flow<List<ConfigProfile>>

    @Query("SELECT * FROM configs ORDER BY isBuiltIn DESC, createdAt ASC")
    suspend fun getAll(): List<ConfigProfile>

    @Query("SELECT * FROM configs WHERE isActive = 1 LIMIT 1")
    fun getActiveFlow(): Flow<ConfigProfile?>

    @Query("SELECT * FROM configs WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ConfigProfile?

    @Query("SELECT * FROM configs WHERE id = :id")
    suspend fun getById(id: Long): ConfigProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ConfigProfile): Long

    @Update
    suspend fun update(config: ConfigProfile)

    @Delete
    suspend fun delete(config: ConfigProfile)

    @Query("UPDATE configs SET isActive = CASE WHEN id = :activeId THEN 1 ELSE 0 END")
    suspend fun setActive(activeId: Long)

    @Query("SELECT COUNT(*) FROM configs")
    suspend fun getCount(): Int

    @Query("SELECT * FROM configs WHERE remoteUrl IS NOT NULL AND autoUpdate = 1")
    suspend fun getAutoUpdateConfigs(): List<ConfigProfile>

    @Query("UPDATE configs SET content = :content, lastUpdated = :lastUpdated WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, lastUpdated: Long)

    @Query("UPDATE configs SET icon = :icon WHERE id = :id")
    suspend fun updateIcon(id: Long, icon: String?)
}
