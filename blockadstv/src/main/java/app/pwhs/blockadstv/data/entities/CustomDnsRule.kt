package app.pwhs.blockadstv.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_dns_rules")
data class CustomDnsRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val rule: String,
    val ruleType: RuleType,
    val domain: String,
    val isEnabled: Boolean = true,
    val addedTimestamp: Long = System.currentTimeMillis(),
)

enum class RuleType {
    BLOCK,
    ALLOW,
}
