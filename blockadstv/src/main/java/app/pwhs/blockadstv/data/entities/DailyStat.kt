package app.pwhs.blockadstv.data.entities

data class DailyStat(
    val day: Long,
    val total: Int,
    val blocked: Int,
)
