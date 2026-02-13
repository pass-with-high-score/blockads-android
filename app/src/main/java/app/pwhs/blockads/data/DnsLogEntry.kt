package app.pwhs.blockads.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dns_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["isBlocked", "domain"]),
        Index(value = ["appName"])
    ]
)
data class DnsLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isBlocked: Boolean,
    val queryType: String = "A",
    val responseTimeMs: Long = 0,
    val appName: String = ""
)
