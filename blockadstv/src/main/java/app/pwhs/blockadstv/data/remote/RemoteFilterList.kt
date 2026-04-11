package app.pwhs.blockadstv.data.remote

data class RemoteFilterList(
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
    val originalUrl: String? = null,
)

data class DownloadedFilterPaths(
    val bloomPath: String?,
    val triePath: String?,
)
