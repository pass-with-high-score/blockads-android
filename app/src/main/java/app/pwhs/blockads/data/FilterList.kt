package app.pwhs.blockads.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "filter_lists")
data class FilterList(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val domainCount: Int = 0,
    val lastUpdated: Long = 0
)
