package app.pwhs.blockads.ui.appmanagement.data

data class AppManagementData(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable,
    val totalQueries: Int = 0,
    val blockedQueries: Int = 0,
    val isWhitelisted: Boolean = false,
    val isSystemApp: Boolean = false
)
