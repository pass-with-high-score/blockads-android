package app.pwhs.blockads.ui.logs.data

import kotlinx.serialization.Serializable

@Serializable
enum class LogFilterStatus {
    ALL,
    BLOCKED,
    THREATS
}
