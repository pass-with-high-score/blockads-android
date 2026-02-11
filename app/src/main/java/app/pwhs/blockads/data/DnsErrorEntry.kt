package app.pwhs.blockads.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dns_errors")
data class DnsErrorEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val timestamp: Long = System.currentTimeMillis(),
    val errorType: String, // "TIMEOUT", "IO_ERROR", "UNKNOWN"
    val errorMessage: String,
    val upstreamDns: String,
    @ColumnInfo(name = "attempted_fallback")
    val attemptedFallback: Boolean = false
)
