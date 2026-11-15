package app.pwhs.blockads.ui.appmanagement.data

data class AppManagementData(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable,
    val uid: Int = -1,
    val totalQueries: Int = 0,
    val blockedQueries: Int = 0,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val wifiRxBytes: Long = 0L,
    val wifiTxBytes: Long = 0L,
    val mobileRxBytes: Long = 0L,
    val mobileTxBytes: Long = 0L,
    val isWhitelisted: Boolean = false,
    val isSystemApp: Boolean = false
) {
    val totalBytes: Long get() = rxBytes + txBytes
    val wifiTotalBytes: Long get() = wifiRxBytes + wifiTxBytes
    val mobileTotalBytes: Long get() = mobileRxBytes + mobileTxBytes
}
