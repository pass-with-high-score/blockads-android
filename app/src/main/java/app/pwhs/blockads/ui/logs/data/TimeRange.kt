package app.pwhs.blockads.ui.logs.data

enum class TimeRange(val millis: Long) {
    ALL(0L),
    HOUR_1(3_600_000L),
    HOUR_6(21_600_000L),
    HOUR_24(86_400_000L),
    DAY_7(604_800_000L)
}