package app.pwhs.blockads.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "element_rules",
    indices = [Index(value = ["domain"])]
)
data class ElementRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val domain: String,
    val cssSelector: String,
    val createdAt: Long = System.currentTimeMillis()
)
