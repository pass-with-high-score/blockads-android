package app.pwhs.blockads.data.entities

data class AppStat(
    val appName: String,
    val packageName: String,
    val totalQueries: Int,
    val blockedQueries: Int
)

data class AppNamePackage(
    val appName: String,
    val packageName: String
)
