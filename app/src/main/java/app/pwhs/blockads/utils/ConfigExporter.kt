package app.pwhs.blockads.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.pwhs.blockads.data.entities.ConfigProfile
import java.io.File

object ConfigExporter {
    fun shareConfig(context: Context, profile: ConfigProfile, chooserTitle: String = "Share Configuration") {
        val configDir = File(context.cacheDir, "configs").apply { if (!exists()) mkdirs() }
        val sanitizedName = profile.name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "config" }
        val exportFile = File(configDir, "$sanitizedName.conf")
        exportFile.writeText(profile.content)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "$sanitizedName.conf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
