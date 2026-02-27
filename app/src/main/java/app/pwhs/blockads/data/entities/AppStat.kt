package app.pwhs.blockads.data.entities

data class AppStat(
    val appName: String,
    val totalQueries: Int,
    val blockedQueries: Int
)