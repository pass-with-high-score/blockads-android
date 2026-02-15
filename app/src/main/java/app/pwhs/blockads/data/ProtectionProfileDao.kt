package app.pwhs.blockads.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtectionProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProtectionProfile): Long

    @Update
    suspend fun update(profile: ProtectionProfile)

    @Delete
    suspend fun delete(profile: ProtectionProfile)

    @Query("SELECT * FROM protection_profiles ORDER BY createdAt ASC")
    fun getAll(): Flow<List<ProtectionProfile>>

    @Query("SELECT * FROM protection_profiles ORDER BY createdAt ASC")
    suspend fun getAllSync(): List<ProtectionProfile>

    @Query("SELECT * FROM protection_profiles WHERE id = :id")
    suspend fun getById(id: Long): ProtectionProfile?

    @Query("SELECT * FROM protection_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ProtectionProfile?

    @Query("SELECT * FROM protection_profiles WHERE isActive = 1 LIMIT 1")
    fun getActiveFlow(): Flow<ProtectionProfile?>

    @Query("SELECT * FROM protection_profiles WHERE profileType = :type LIMIT 1")
    suspend fun getByType(type: String): ProtectionProfile?

    @Query("UPDATE protection_profiles SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE protection_profiles SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    // Schedule queries
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ProfileSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: ProfileSchedule)

    @Delete
    suspend fun deleteSchedule(schedule: ProfileSchedule)

    @Query("SELECT * FROM profile_schedules WHERE profileId = :profileId")
    fun getSchedulesForProfile(profileId: Long): Flow<List<ProfileSchedule>>

    @Query("SELECT * FROM profile_schedules WHERE isEnabled = 1")
    suspend fun getEnabledSchedules(): List<ProfileSchedule>

    @Query("SELECT * FROM profile_schedules")
    fun getAllSchedules(): Flow<List<ProfileSchedule>>
}
