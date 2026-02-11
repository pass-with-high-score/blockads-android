package app.pwhs.blockads.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dns_logs")
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
