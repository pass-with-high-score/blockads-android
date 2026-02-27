package app.pwhs.blockads.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "protection_profiles")
data class ProtectionProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val profileType: String,
    val enabledFilterUrls: String = "",
    val safeSearchEnabled: Boolean = false,
    val youtubeRestrictedMode: Boolean = false,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_DEFAULT = "DEFAULT"
        const val TYPE_STRICT = "STRICT"
        const val TYPE_FAMILY = "FAMILY"
        const val TYPE_GAMING = "GAMING"
        const val TYPE_CUSTOM = "CUSTOM"

        fun isPreset(type: String): Boolean =
            type in listOf(TYPE_DEFAULT, TYPE_STRICT, TYPE_FAMILY, TYPE_GAMING)
    }
}
