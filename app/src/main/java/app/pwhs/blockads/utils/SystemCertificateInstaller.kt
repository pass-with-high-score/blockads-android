package app.pwhs.blockads.utils

import com.topjohnwu.superuser.Shell
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/**
 * Utility to install BlockAds Root CA directly into the Android System CA Store
 * on rooted devices (Magisk, KernelSU, APatch).
 *
 * This provides system-wide HTTPS filtering for all applications without triggering
 * user-certificate warnings or broken SSL errors.
 */
object SystemCertificateInstaller {

    private const val MODULE_ID = "blockads_ca"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

    fun isRootAvailable(): Boolean {
        return try {
            Shell.isAppGrantedRoot() == true || Shell.cmd("id").exec().isSuccess
        } catch (e: Exception) {
            Timber.w(e, "Failed to check root availability")
            false
        }
    }

    /**
     * Computes the OpenSSL subject hash (MD5-based, old style used by Android cacerts).
     */
    fun computeSubjectHashOld(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(cert.subjectX500Principal.encoded)
        val hash = (digest[0].toInt() and 0xFF) or
                ((digest[1].toInt() and 0xFF) shl 8) or
                ((digest[2].toInt() and 0xFF) shl 16) or
                ((digest[3].toInt() and 0xFF) shl 24)
        return String.format(Locale.US, "%08x", hash.toLong() and 0xFFFFFFFFL)
    }

    /**
     * Computes the OpenSSL subject hash (SHA-1 based, newer style used by Conscrypt APEX).
     */
    fun computeSubjectHashSha1(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(cert.subjectX500Principal.encoded)
        val hash = (digest[0].toInt() and 0xFF) or
                ((digest[1].toInt() and 0xFF) shl 8) or
                ((digest[2].toInt() and 0xFF) shl 16) or
                ((digest[3].toInt() and 0xFF) shl 24)
        return String.format(Locale.US, "%08x", hash.toLong() and 0xFFFFFFFFL)
    }

    /**
     * Installs the CA certificate directly to the Android User CA Store via root.
     * Path: /data/misc/user/0/cacerts-added/<hash>.0
     *
     * This takes effect IMMEDIATELY without needing a device reboot, because
     * AndroidCAStore dynamically loads user certificates from /data.
     */
    fun installToUserStoreViaRoot(caPem: String): Result<String> {
        if (!isRootAvailable()) {
            return Result.failure(IllegalStateException("Root access is not available"))
        }

        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(
                ByteArrayInputStream(caPem.toByteArray())
            ) as X509Certificate

            val hashOld = computeSubjectHashOld(cert)
            val userStoreDir = "/data/misc/user/0/cacerts-added"
            val certPath = "$userStoreDir/$hashOld.0"
            val removedPath = "/data/misc/user/0/cacerts-removed/$hashOld.0"

            val commands = listOf(
                "mkdir -p $userStoreDir",
                "cat << 'EOF' > $certPath\n$caPem\nEOF",
                "chmod 644 $certPath",
                "chown system:system $certPath 2>/dev/null || true",
                "rm -f $removedPath"
            )

            val res = Shell.cmd(*commands.toTypedArray()).exec()
            if (res.isSuccess) {
                Timber.d("CA installed to user store via root successfully: $certPath")
                Result.success(hashOld)
            } else {
                val err = res.err.joinToString("\n")
                Timber.e("Failed to install CA to user store: $err")
                Result.failure(RuntimeException(err))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during user store root install")
            Result.failure(e)
        }
    }

    /**
     * Installs the CA certificate as a Magisk / KernelSU module so it mounts
     * automatically into /system/etc/security/cacerts/ and Conscrypt APEX.
     */
    fun installToSystemStore(caPem: String): Result<String> {
        if (!isRootAvailable()) {
            return Result.failure(IllegalStateException("Root access is not available"))
        }

        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(
                ByteArrayInputStream(caPem.toByteArray())
            ) as X509Certificate

            val hashOld = computeSubjectHashOld(cert)
            val hashSha1 = computeSubjectHashSha1(cert)

            val commands = mutableListOf<String>()

            // 1. Prepare Magisk / KernelSU module structure
            commands.add("mkdir -p $MODULE_DIR/system/etc/security/cacerts")
            commands.add("mkdir -p $MODULE_DIR/system/apex/com.android.conscrypt/cacerts")

            // 2. Write module.prop
            val moduleProp = """
                id=$MODULE_ID
                name=BlockAds Root CA
                version=1.1
                versionCode=2
                author=BlockAds
                description=System CA Certificate for BlockAds HTTPS Filtering
            """.trimIndent()
            commands.add("cat << 'EOF' > $MODULE_DIR/module.prop\n$moduleProp\nEOF")

            // 3. Write certificate files (both old MD5 and new SHA1 hashes)
            for (hash in setOf(hashOld, hashSha1)) {
                val certPath1 = "$MODULE_DIR/system/etc/security/cacerts/$hash.0"
                val certPath2 = "$MODULE_DIR/system/apex/com.android.conscrypt/cacerts/$hash.0"
                commands.add("cat << 'EOF' > $certPath1\n$caPem\nEOF")
                commands.add("chmod 644 $certPath1")
                commands.add("cat << 'EOF' > $certPath2\n$caPem\nEOF")
                commands.add("chmod 644 $certPath2")
            }

            // 4. Also write service.sh to mount into Conscrypt APEX on boot for Android 14+
            val serviceScript = """
                #!/system/bin/sh
                # Mount certificate into Conscrypt APEX on Android 14+
                APEX_DIR="/apex/com.android.conscrypt/cacerts"
                if [ -d "${'$'}APEX_DIR" ]; then
                    mount -t tmpfs tmpfs "${'$'}APEX_DIR" 2>/dev/null || true
                    cp /system/etc/security/cacerts/* "${'$'}APEX_DIR/" 2>/dev/null || true
                    chmod 644 "${'$'}APEX_DIR"/* 2>/dev/null || true
                fi
            """.trimIndent()
            commands.add("cat << 'EOF' > $MODULE_DIR/service.sh\n$serviceScript\nEOF")
            commands.add("chmod 755 $MODULE_DIR/service.sh")

            // 5. Also install immediately to user store so it works right away without reboot!
            val userStoreDir = "/data/misc/user/0/cacerts-added"
            commands.add("mkdir -p $userStoreDir")
            commands.add("cat << 'EOF' > $userStoreDir/$hashOld.0\n$caPem\nEOF")
            commands.add("chmod 644 $userStoreDir/$hashOld.0")
            commands.add("chown system:system $userStoreDir/$hashOld.0 2>/dev/null || true")

            val res = Shell.cmd(*commands.toTypedArray()).exec()
            if (res.isSuccess) {
                Timber.d("CA installed to system store module successfully (hashOld=$hashOld, hashSha1=$hashSha1)")
                Result.success(hashOld)
            } else {
                val err = res.err.joinToString("\n")
                Timber.e("Failed to install CA to system store: $err")
                Result.failure(RuntimeException(err))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during system CA installation")
            Result.failure(e)
        }
    }
}
