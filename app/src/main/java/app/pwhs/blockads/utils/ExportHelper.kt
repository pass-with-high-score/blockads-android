package app.pwhs.blockads.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.io.OutputStream

/**
 * Unified helper for exporting files, resolving target SAF/MediaStore URIs,
 * and providing robust multi-tier fallbacks across document creation, folder selection,
 * share sheets, and public Downloads storage.
 */
object ExportHelper {

    /**
     * Resolves a target [Uri] for exporting data.
     * 1. If [uri] is null: creates an entry in public Downloads directory.
     * 2. If [uri] is a tree URI (from OpenDocumentTree): creates a document inside that directory.
     * 3. Otherwise: returns [uri] directly.
     */
    fun resolveTargetUri(
        context: Context,
        uri: Uri?,
        defaultFileName: String,
        mimeType: String = "text/plain"
    ): Uri {
        if (uri == null) {
            return resolveDownloadsUri(context, defaultFileName, mimeType)
        }
        if (DocumentsContract.isTreeUri(uri)) {
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
            return DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeType,
                defaultFileName
            ) ?: resolveDownloadsUri(context, defaultFileName, mimeType)
        }
        return uri
    }

    /**
     * Creates an entry in the system Downloads directory.
     * Uses MediaStore.Downloads on Android 10+ (Q), or public external storage on older Android.
     */
    fun resolveDownloadsUri(
        context: Context,
        fileName: String,
        mimeType: String = "text/plain"
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            return context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("Failed to insert MediaStore download entry")
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .takeIf { it.canWrite() || it.mkdirs() }
                ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "exports").apply { mkdirs() }
            return Uri.fromFile(File(downloadsDir, fileName))
        }
    }

    /**
     * Opens an [OutputStream] for the given [Uri], supporting both `file://` and `content://` schemes.
     */
    fun openOutputStream(context: Context, uri: Uri): OutputStream? {
        return if (uri.scheme == "file") {
            uri.path?.let { File(it).outputStream() }
        } else {
            context.contentResolver.openOutputStream(uri)
        }
    }

    /**
     * Writes raw text into destination [uri].
     */
    fun writeTextToUri(context: Context, uri: Uri, text: String) {
        val stream = openOutputStream(context, uri)
            ?: throw IllegalStateException("Cannot open output stream for URI: $uri")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Copies an existing [sourceFile] into the destination [targetUri].
     */
    fun copyFileToUri(context: Context, sourceFile: File, targetUri: Uri) {
        val stream = openOutputStream(context, targetUri)
            ?: throw IllegalStateException("Cannot open output stream for URI: $targetUri")
        stream.use { out ->
            sourceFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }
    }

    /**
     * Copies data from a [sourceUri] stream directly to a new file in Downloads.
     */
    fun copyUriToDownloads(
        context: Context,
        sourceUri: Uri,
        fileName: String,
        mimeType: String = "text/plain"
    ): Uri {
        val targetUri = resolveDownloadsUri(context, fileName, mimeType)
        val out = openOutputStream(context, targetUri)
            ?: throw IllegalStateException("Cannot open output stream for URI: $targetUri")
        out.use { output ->
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                input.copyTo(output)
            } ?: throw IllegalStateException("Cannot open input stream for URI: $sourceUri")
        }
        return targetUri
    }

    /**
     * Saves a [sourceFile] into the public Downloads directory.
     * Returns the created [Uri].
     */
    fun saveFileToDownloads(
        context: Context,
        sourceFile: File,
        mimeType: String = "text/plain",
        targetFileName: String = sourceFile.name
    ): Uri {
        val targetUri = resolveDownloadsUri(context, targetFileName, mimeType)
        copyFileToUri(context, sourceFile, targetUri)
        return targetUri
    }

    /**
     * Attempts to launch an ACTION_SEND share sheet for [file].
     * Returns true if successfully started, or false if an exception occurred.
     */
    fun shareFile(
        context: Context,
        file: File,
        mimeType: String = "text/plain",
        subject: String? = null,
        chooserTitle: String? = null
    ): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            shareUri(context, uri, mimeType, subject, chooserTitle)
        } catch (e: Exception) {
            Timber.w(e, "Failed to share file: ${file.name}")
            false
        }
    }

    /**
     * Attempts to launch an ACTION_SEND share sheet for [uri].
     * Returns true if successfully started, or false if an exception occurred.
     */
    fun shareUri(
        context: Context,
        uri: Uri,
        mimeType: String = "text/plain",
        subject: String? = null,
        chooserTitle: String? = null
    ): Boolean {
        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                if (subject != null) {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to launch chooser for uri: $uri")
            false
        }
    }

    /**
     * Shares a file via ACTION_SEND. If the share action fails, automatically falls back
     * to saving the file directly into the public Downloads directory.
     *
     * @return Pair<Boolean, Uri?> where first indicates if share succeeded,
     * and second is the fallback URI if saved to Downloads.
     */
    fun shareFileWithDownloadsFallback(
        context: Context,
        file: File,
        mimeType: String = "text/plain",
        subject: String? = null,
        chooserTitle: String? = null,
        targetFileName: String = file.name
    ): Pair<Boolean, Uri?> {
        val shared = shareFile(context, file, mimeType, subject, chooserTitle)
        if (shared) {
            return true to null
        }
        Timber.i("Share sheet failed; falling back to saving file to Downloads: ${file.name}")
        val downloadsUri = saveFileToDownloads(context, file, mimeType, targetFileName)
        return false to downloadsUri
    }
}
