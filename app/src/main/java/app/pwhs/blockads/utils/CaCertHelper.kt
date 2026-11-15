package app.pwhs.blockads.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Shared helper for Root CA certificate verification and system settings intent.
 */
object CaCertHelper {

    /**
     * Checks if the Root CA represented by [caPem] is installed in the Android trust store.
     */
    fun checkCertInTrustStore(caPem: String?): Boolean {
        if (caPem.isNullOrEmpty()) return false
        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val ourCert = certFactory.generateCertificate(caPem.byteInputStream()) as X509Certificate
            val ourEncoded = ourCert.encoded

            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)

            for (alias in ks.aliases()) {
                val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                if (cert.encoded.contentEquals(ourEncoded)) {
                    Timber.d("Cert verification: found matching CA in trust store (alias=$alias)")
                    return true
                }
            }

            false
        } catch (e: Exception) {
            Timber.e(e, "Cert verification failed")
            false
        }
    }

    /**
     * Creates an Intent to open system security settings for certificate installation.
     */
    fun createSecuritySettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent("android.settings.SECURITY_SETTINGS")
        } else {
            Intent("android.credentials.INSTALL").apply {
                type = "application/x-x509-ca-cert"
            }
        }
    }

    /**
     * Saves [pem] into the public Downloads directory.
     * Returns `null` on Android 10+ (saved via MediaStore) or the legacy [File] on Android 9-.
     */
    fun saveCertToDownloads(context: Context, pem: String, fileName: String): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName)
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
                ?: throw IllegalStateException("Failed to open output stream")
            return null
        }

        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val file = File(downloadsDir, fileName)
        file.writeText(pem)
        return file
    }

    /**
     * Saves [pem] to a user-selected SAF [uri].
     */
    fun saveCertToUri(context: Context, pem: String, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
            ?: throw IllegalStateException("Failed to open output stream")
    }
}
