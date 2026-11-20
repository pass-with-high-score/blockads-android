package app.pwhs.blockads.ui.logs.data

import app.pwhs.blockads.data.entities.DnsLogEntry

enum class LogGrouping {
    NONE,
    BY_DOMAIN,
    BY_APP
}

data class LogGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val packageName: String,
    val totalCount: Int,
    val blockedCount: Int,
    val latestTimestamp: Long,
    val entries: List<DnsLogEntry>
)

fun groupLogs(
    logs: List<DnsLogEntry>,
    grouping: LogGrouping,
    unknownAppLabel: String
): List<LogGroup> {
    if (grouping == LogGrouping.NONE || logs.isEmpty()) return emptyList()

    val grouped = when (grouping) {
        LogGrouping.BY_DOMAIN -> logs.groupBy { it.domain.lowercase() }
        LogGrouping.BY_APP -> logs.groupBy { it.appName.ifBlank { unknownAppLabel } }
        LogGrouping.NONE -> return emptyList()
    }

    return grouped.map { (key, groupEntries) ->
        val latest = groupEntries.maxByOrNull { it.timestamp } ?: groupEntries.first()
        val blockedCount = groupEntries.count { it.isBlocked }
        val title = when (grouping) {
            LogGrouping.BY_DOMAIN -> latest.domain
            LogGrouping.BY_APP -> key
            LogGrouping.NONE -> key
        }
        val subtitle = when (grouping) {
            LogGrouping.BY_DOMAIN -> {
                val distinctApps = groupEntries.mapNotNull { it.appName.takeIf(String::isNotBlank) }.distinct()
                when {
                    distinctApps.isEmpty() -> ""
                    distinctApps.size == 1 -> distinctApps.first()
                    else -> "${distinctApps.first()} +${distinctApps.size - 1}"
                }
            }
            LogGrouping.BY_APP -> {
                val distinctDomains = groupEntries.map { it.domain.lowercase() }.distinct()
                distinctDomains.firstOrNull().orEmpty()
            }
            LogGrouping.NONE -> ""
        }
        val packageName = groupEntries.firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()

        LogGroup(
            key = key,
            title = title,
            subtitle = subtitle,
            packageName = packageName,
            totalCount = groupEntries.size,
            blockedCount = blockedCount,
            latestTimestamp = latest.timestamp,
            entries = groupEntries
        )
    }.sortedByDescending { it.latestTimestamp }
}
