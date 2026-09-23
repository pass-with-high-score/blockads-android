package app.pwhs.blockads.utils

import android.content.Context
import android.content.res.AssetFileDescriptor
import timber.log.Timber

/**
 * Zero-copy file descriptor wrapper for passing blocklists and rules
 * directly from Android assets to native Go/C engine without JVM heap allocation.
 * Inspired by ExpressVPN's ThreatManager BlocklistInfo.
 */
data class BlocklistInfo(
    val fd: Long,
    val startOffset: Long,
    val length: Long,
    private val afd: AssetFileDescriptor? = null
) : AutoCloseable {

    override fun close() {
        try {
            afd?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing BlocklistInfo afd")
        }
    }

    companion object {
        fun fromAsset(context: Context, assetName: String): BlocklistInfo? {
            return try {
                val afd = context.assets.openFd(assetName)
                BlocklistInfo(
                    fd = afd.parcelFileDescriptor.fd.toLong(),
                    startOffset = afd.startOffset,
                    length = afd.length,
                    afd = afd
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to open asset fd for $assetName")
                null
            }
        }
    }
}
