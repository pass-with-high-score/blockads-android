package app.pwhs.blockadstv.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Quantumult X-compatible configuration or snippet ruleset.
 */
@Entity(tableName = "configs")
data class ConfigProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val content: String = "",
    val remoteUrl: String? = null,
    val autoUpdate: Boolean = true,
    val lastUpdated: Long = 0L,
    val isActive: Boolean = false,
    val isBuiltIn: Boolean = false,
    val icon: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
