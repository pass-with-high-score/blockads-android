package app.pwhs.blockads.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "whitelist_domains")
data class WhitelistDomain(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val domain: String,
    val addedTimestamp: Long = System.currentTimeMillis()
)