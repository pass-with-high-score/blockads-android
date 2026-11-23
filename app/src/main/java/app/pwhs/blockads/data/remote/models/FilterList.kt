package app.pwhs.blockads.data.remote.models

import kotlinx.serialization.Serializable

@Serializable
data class FilterStats(
    val domains: Int = 0,
    val ips: Int = 0,
    val cosmetic: Int = 0,
    val cosmeticExceptions: Int = 0,
    val injectCss: Int = 0,
    val scriptlets: Int = 0,
    val scriptletExceptions: Int = 0,
    val blockPaths: Int = 0,
    val removeParams: Int = 0,
)

/**
 * Data class representing a filter list from the remote or bundled filter_lists.json.
 * Used for syncing pre-compiled filter URLs from the server.
 */
@Serializable
data class FilterList(
    val name: String,
    val id: String = "",
    val description: String? = null,
    val isEnabled: Boolean = false,
    val isBuiltIn: Boolean = true,
    val category: String? = null,
    val ruleCount: Int = 0,
    val bloomUrl: String = "",
    val trieUrl: String = "",
    val cssUrl: String? = null,
    val scriptletsUrl: String? = null,
    val originalUrl: String? = null,
    val downloadUrl: String? = null,
    val stats: FilterStats? = null,
)
