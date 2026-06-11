package app.pwhs.blockadstv.data.entities

data class HourlyStat(
    val hour: Long,
    val total: Int,
    val blocked: Int,
)
