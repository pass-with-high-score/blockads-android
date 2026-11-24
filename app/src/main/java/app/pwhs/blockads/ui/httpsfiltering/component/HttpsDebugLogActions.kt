package app.pwhs.blockads.ui.httpsfiltering.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.pwhs.blockads.data.logging.HttpDebugLogEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HttpsDebugLogActions {

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun extractHost(entry: HttpDebugLogEntry): String {
        val parsed = runCatching { Uri.parse(entry.url).host }.getOrNull()
        return (parsed ?: entry.initiator).trim().lowercase(Locale.US)
    }

    fun addHostToMitmBlacklist(context: Context, host: String): Boolean {
        val clean = host.trim().lowercase(Locale.US)
        if (clean.isEmpty() || clean.contains(" ")) return false
        val file = File(context.filesDir, "mitm_blacklist.txt")
        val existing = if (file.exists()) {
            file.readLines().map { it.trim().lowercase(Locale.US) }
        } else {
            emptyList()
        }
        if (existing.contains(clean)) return false
        file.appendText("$clean\n")
        return true
    }

    fun createLogFile(context: Context, entries: List<HttpDebugLogEntry>): File {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val dir = File(context.cacheDir, "logs").apply { if (!exists()) mkdirs() }
        val file = File(dir, "blockads_http_logs_${System.currentTimeMillis()}.txt")
        file.bufferedWriter().use { out ->
            out.appendLine("# BlockAds HTTP & Scriptlet Debug Logs (${entries.size} entries)")
            out.appendLine()
            for (e in entries) {
                out.appendLine("[${fmt.format(Date(e.timestamp))}] [${e.action.name}] [${e.source}] ${e.method} ${e.resourceType} | rule=${e.rule.ifBlank { "-" }} | initiator=${e.initiator.ifBlank { "-" }} | ${e.url}")
                if (!e.detail.isNullOrBlank()) {
                    out.appendLine("  detail: ${e.detail.replace("\n", "\n  ")}")
                }
            }
        }
        return file
    }

    fun exportAndShare(context: Context, entries: List<HttpDebugLogEntry>): Result<Unit> = runCatching {
        val file = createLogFile(context, entries)
        app.pwhs.blockads.utils.ExportHelper.shareFileWithDownloadsFallback(
            context = context,
            file = file,
            mimeType = "text/plain",
            chooserTitle = "Export HTTP Logs"
        )
    }

    fun exportToUri(context: Context, entries: List<HttpDebugLogEntry>, uri: Uri): Result<Unit> = runCatching {
        val file = createLogFile(context, entries)
        val targetUri = app.pwhs.blockads.utils.ExportHelper.resolveTargetUri(context, uri, file.name, "text/plain")
        app.pwhs.blockads.utils.ExportHelper.copyFileToUri(context, file, targetUri)
    }
}
