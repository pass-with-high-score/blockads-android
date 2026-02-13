package app.pwhs.blockads.data

data class AppStat(
    val appName: String,
    val totalQueries: Int,
    val blockedQueries: Int
)
