package app.pwhs.blockads.data.remote.models

/**
 * Data class representing a filter list from the remote filter_lists.json.
 * Used for syncing pre-compiled filter URLs from the server.
 */
data class FilterList(
    val name: String,
    val id: String,
    val description: String? = null,
    val isEnabled: Boolean = false,
    val isBuiltIn: Boolean = true,
    val category: String? = null,
    val ruleCount: Int = 0,
    val bloomUrl: String,
    val trieUrl: String,
    val cssUrl: String? = null,
    val scriptletsUrl: String? = null,
    val originalUrl: String? = null
)
