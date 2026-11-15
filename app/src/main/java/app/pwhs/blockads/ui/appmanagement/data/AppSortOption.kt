package app.pwhs.blockads.ui.appmanagement.data

enum class AppSortOption {
    NAME, QUERIES, BLOCKED, DATA_USAGE
}

enum class AppUsageTimeRange(val days: Int) {
    TODAY(1),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30)
}
