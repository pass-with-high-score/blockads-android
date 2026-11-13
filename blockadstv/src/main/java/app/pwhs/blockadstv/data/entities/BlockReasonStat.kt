package app.pwhs.blockadstv.data.entities

data class BlockReasonRawStat(
    val blockedBy: String,
    val count: Int,
)

data class BlockReasonStat(
    val reasonKey: String,
    val displayName: String,
    val count: Int,
    val filterId: Long? = null,
    val category: String = "",
)
