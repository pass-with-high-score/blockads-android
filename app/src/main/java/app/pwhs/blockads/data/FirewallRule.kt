package app.pwhs.blockads.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "firewall_rules",
    indices = [Index(value = ["packageName"], unique = true)]
)
data class FirewallRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val blockWifi: Boolean = true,
    val blockMobileData: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = 22,
    val scheduleStartMinute: Int = 0,
    val scheduleEndHour: Int = 6,
    val scheduleEndMinute: Int = 0,
    val isEnabled: Boolean = true
)
