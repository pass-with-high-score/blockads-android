package app.pwhs.blockads.service.tailscale

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.pwhs.blockads.R
import app.pwhs.blockads.data.datastore.prefs.TailscalePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import tunnel.TaildropReceiver
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLConnection

class TailscaleTaildropManager(
    private val context: Context,
    private val tailscalePreferences: TailscalePreferences,
    private val scope: CoroutineScope,
) : TaildropReceiver {

    companion object {
        const val CHANNEL_ID = "blockads_tailscale_channel"
    }

    override fun onFileReceived(fileName: String, tempPath: String, size: Long): Boolean {
        Timber.d("Taildrop file received: $fileName ($size bytes) at $tempPath")
        scope.launch(Dispatchers.IO) {
            handleIncomingFile(fileName, tempPath, size)
        }
        return true
    }

    private suspend fun handleIncomingFile(fileName: String, tempPath: String, size: Long) {
        val tempFile = File(tempPath)
        if (!tempFile.exists()) {
            Timber.e("Taildrop temp file does not exist: $tempPath")
            return
        }

        try {
            val dirUriString = tailscalePreferences.taildropDirUri.first()
            val mimeType = URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
            var savedUri: Uri? = null

            // 1. If user selected a custom SAF directory
            if (dirUriString.isNotEmpty()) {
                runCatching {
                    val treeUri = dirUriString.toUri()
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    val newDocUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentDocUri,
                        mimeType,
                        fileName
                    )
                    if (newDocUri != null) {
                        context.contentResolver.openOutputStream(newDocUri)?.use { out ->
                            FileInputStream(tempFile).use { input ->
                                input.copyTo(out)
                            }
                        }
                        savedUri = newDocUri
                    }
                }.onFailure {
                    Timber.w(it, "Failed to save to SAF document URI, falling back to public Download/Taildrop")
                }
            }

            // 2. Default: Save directly to public shared "Download/Taildrop" folder
            if (savedUri == null) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Taildrop")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                FileInputStream(tempFile).use { input ->
                                    input.copyTo(out)
                                }
                            }
                            contentValues.clear()
                            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            context.contentResolver.update(uri, contentValues, null, null)
                            savedUri = uri
                        }
                    } else {
                        val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val tdDir = File(pubDownloads, "Taildrop").apply { mkdirs() }
                        val targetFile = File(tdDir, fileName)
                        FileInputStream(tempFile).use { input ->
                            FileOutputStream(targetFile).use { out ->
                                input.copyTo(out)
                            }
                        }
                        savedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                    }
                }.onFailure {
                    Timber.w(it, "Failed to save to public Download/Taildrop, falling back to app files dir")
                }
            }

            // 3. Fallback to app external files dir if MediaStore insertion failed
            if (savedUri == null) {
                val fallbackFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "taildrop").apply { mkdirs() }
                val targetFile = File(fallbackFolder, fileName)
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(targetFile).use { out ->
                        input.copyTo(out)
                    }
                }
                savedUri = runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                }.getOrNull() ?: Uri.fromFile(targetFile)
            }

            tempFile.delete()
            showNotification(fileName, size, savedUri)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist Taildrop file $fileName")
        }
    }

    private fun showNotification(fileName: String, size: Long, fileUri: Uri?) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.tailscale_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.tailscale_notification_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = fileUri?.let { uri ->
            val mimeType = URLConnection.guessContentTypeFromName(fileName) ?: "*/*"
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val pendingIntent = openIntent?.let {
            PendingIntent.getActivity(
                context,
                fileName.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val sizeFormatted = Formatter.formatFileSize(context, size)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.tailscale_taildrop_received_title))
            .setContentText(context.getString(R.string.tailscale_taildrop_received_text, fileName, sizeFormatted))
            .setSmallIcon(R.drawable.ic_shield_on)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
            builder.addAction(
                R.drawable.ic_shield_on,
                context.getString(R.string.tailscale_taildrop_open_file),
                pendingIntent
            )
        }

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
    }
}
