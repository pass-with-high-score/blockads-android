package app.pwhs.blockadstv.data.entities

data class MonthlyStat(
    val month: String,
    val total: Int,
    val blocked: Int,
)
