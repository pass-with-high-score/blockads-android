package app.pwhs.blockads.ui.browser

import android.webkit.URLUtil
import java.net.URLDecoder

/**
 * Extracts a human-readable filename from Content-Disposition header, URL, or MIME type.
 * Handles URL-encoded characters (e.g. `+` for spaces) that URLUtil.guessFileName misses.
 */
fun extractFileName(url: String, contentDisposition: String?, mimeType: String?): String {
    // Try Content-Disposition header first
    contentDisposition?.let { cd ->
        val regex = Regex("""filename\*?=\s*(?:UTF-8''|")?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
        regex.find(cd)?.groupValues?.get(1)?.let { raw ->
            val decoded = runCatching { URLDecoder.decode(raw.trim(), "UTF-8") }.getOrDefault(raw.trim())
            if (decoded.isNotBlank() && decoded.contains('.')) return sanitizeFileName(decoded)
        }
    }

    // Try URL query param 'filename'
    runCatching { android.net.Uri.parse(url) }.getOrNull()?.let { uri ->
        uri.getQueryParameter("filename")?.let { raw ->
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            if (decoded.isNotBlank() && decoded.contains('.')) return sanitizeFileName(decoded)
        }
    }

    // Fallback to URLUtil
    val fallback = URLUtil.guessFileName(url, contentDisposition, mimeType)
    return sanitizeFileName(
        runCatching { URLDecoder.decode(fallback, "UTF-8") }.getOrDefault(fallback)
    )
}

private fun sanitizeFileName(name: String): String {
    return name
        .replace('+', ' ')
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifBlank { "download" }
}
