package app.pwhs.blockads.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dns_errors")
data class DnsErrorEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "error_type")
    val errorType: String, // "TIMEOUT", "IO_ERROR", "UNKNOWN"
    @ColumnInfo(name = "error_message")
    val errorMessage: String,
    @ColumnInfo(name = "upstream_dns")
    val dnsServer: String,
    @ColumnInfo(name = "attempted_fallback")
    val attemptedFallback: Boolean = false
)
