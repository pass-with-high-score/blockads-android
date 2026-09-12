package app.pwhs.blockads.ui.browser.rules

import kotlinx.serialization.Serializable

/**
 * Encapsulates the complete browser ad blocking rule package.
 * Can be loaded from local cache or updated over-the-air from remote JSON.
 */
@Serializable
data class BrowserRulePackage(
    val version: Long = 1L,
    val updatedAt: Long = System.currentTimeMillis(),
    val adDomains: List<String> = emptyList(),
    val gamblingKeywords: List<String> = emptyList(),
    val adPathPatterns: List<String> = emptyList(),
    val cosmeticCss: String? = null,
    val scriptletsJs: String? = null,
)

/**
 * Status of dynamic rule checking and updating.
 */
sealed interface BrowserRuleUpdateStatus {
    data object Idle : BrowserRuleUpdateStatus
    data object Checking : BrowserRuleUpdateStatus
    data class Updated(val version: Long, val domainsCount: Int) : BrowserRuleUpdateStatus
    data class UpToDate(val version: Long) : BrowserRuleUpdateStatus
    data class Error(val message: String) : BrowserRuleUpdateStatus
}
