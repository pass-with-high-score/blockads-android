package app.pwhs.blockads.data

data class HourlyStat(
    val hour: Long,
    val total: Int,
    val blocked: Int
)
