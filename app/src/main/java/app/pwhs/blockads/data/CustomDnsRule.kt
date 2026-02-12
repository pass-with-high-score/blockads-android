package app.pwhs.blockads.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_dns_rules")
data class CustomDnsRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val rule: String, // Raw rule text (e.g., "||example.com^", "@@||example.com^", "! comment")
    val ruleType: RuleType, // BLOCK, ALLOW, COMMENT
    val domain: String, // Parsed domain (empty for comments)
    val isEnabled: Boolean = true,
    val addedTimestamp: Long = System.currentTimeMillis()
)

enum class RuleType {
    BLOCK,   // Block domain (||example.com^ or example.com)
    ALLOW,   // Allow domain (@@||example.com^)
    COMMENT  // Comment line (! comment)
}
