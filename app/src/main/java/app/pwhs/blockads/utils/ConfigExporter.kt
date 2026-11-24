package app.pwhs.blockads.utils

import android.content.Context
import android.net.Uri
import app.pwhs.blockads.data.entities.ConfigProfile
import java.io.File

object ConfigExporter {
    fun shareConfig(
        context: Context,
        profile: ConfigProfile,
        chooserTitle: String = "Share Configuration"
    ): Pair<Boolean, Uri?> {
        val configDir = File(context.cacheDir, "configs").apply { if (!exists()) mkdirs() }
        val sanitizedName = profile.name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "config" }
        val exportFile = File(configDir, "$sanitizedName.conf")
        exportFile.writeText(profile.content)

        return ExportHelper.shareFileWithDownloadsFallback(
            context = context,
            file = exportFile,
            mimeType = "text/plain",
            subject = "$sanitizedName.conf",
            chooserTitle = chooserTitle
        )
    }

    fun exportToUri(context: Context, profile: ConfigProfile, uri: Uri? = null): Uri {
        val sanitizedName = profile.name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "config" }
        val targetUri = ExportHelper.resolveTargetUri(context, uri, "$sanitizedName.conf", "text/plain")
        ExportHelper.writeTextToUri(context, targetUri, profile.content)
        return targetUri
    }
}
