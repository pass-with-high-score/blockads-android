package app.pwhs.blockads.utils

import app.pwhs.blockads.testutil.FakeRootShell
import app.pwhs.blockads.testutil.FakeRootShell.FakeResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** The root commands SystemCertificateInstaller issues, over [FakeRootShell]. */
class SystemCertificateInstallerShellTest {

    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIICADCCAaegAwIBAgIUamyDWFnTP9jEV30xpa2qQKwketIwCgYIKoZIzj0EAwIw
        VjELMAkGA1UEBhMCVVMxFzAVBgNVBAoMDkJsb2NrQWRzICBUZXN0MRMwEQYDVQQL
        DApVbml0IFRlc3RzMRkwFwYDVQQDDBBCbG9ja0FkcyBUZXN0IENBMB4XDTI2MDky
        MzE4MTkwMloXDTM2MDkyMDE4MTkwMlowVjELMAkGA1UEBhMCVVMxFzAVBgNVBAoM
        DkJsb2NrQWRzICBUZXN0MRMwEQYDVQQLDApVbml0IFRlc3RzMRkwFwYDVQQDDBBC
        bG9ja0FkcyBUZXN0IENBMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE2Pf8HoLv
        y04mzz94LLU4+g2EXTRGunaw/Udy/7QKQdn+9DHlUBewy+VgFlKXhXSsHyzX5ctz
        zFjHP5pHc5nN6qNTMFEwHQYDVR0OBBYEFBeKMtJ+w5ob8+1Yhj4ZlGtOYYVKMB8G
        A1UdIwQYMBaAFBeKMtJ+w5ob8+1Yhj4ZlGtOYYVKMA8GA1UdEwEB/wQFMAMBAf8w
        CgYIKoZIzj0EAwIDRwAwRAIgYrnoSCFWfc9+Ml1BqxSE5yUzUjc1fJL4LRpMQ5wY
        ikECIBPxLfTdz39kDgHT9O2MarTJEU8ijSz0tEjUR5ND/9GL
        -----END CERTIFICATE-----
    """.trimIndent()

    private val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.byteInputStream()) as X509Certificate
    private val shell = FakeRootShell().apply { grantedRoot = true }

    @Before
    fun setUp() {
        SystemCertificateInstaller.shell = shell
    }

    @After
    fun tearDown() {
        SystemCertificateInstaller.shell = LibsuRootShell
    }

    @Test
    fun `granted root short-circuits the id probe`() {
        assertTrue(SystemCertificateInstaller.isRootAvailable())
        assertTrue(shell.batches.isEmpty())
    }

    @Test
    fun `without root neither install touches the filesystem`() {
        shell.grantedRoot = null
        shell.respond = { FakeResult(isSuccess = false) }
        assertTrue(SystemCertificateInstaller.installToUserStoreViaRoot(pem).isFailure)
        assertTrue(SystemCertificateInstaller.installToSystemStore(pem).isFailure)
        assertEquals(listOf(listOf("id"), listOf("id")), shell.batches)
    }

    @Test
    fun `user store install writes the cert under its old subject hash in one batch`() {
        val result = SystemCertificateInstaller.installToUserStoreViaRoot(pem)
        assertEquals("6572d38d", result.getOrThrow())
        val path = "/data/misc/user/0/cacerts-added/6572d38d.0"
        assertEquals(
            listOf(
                "mkdir -p /data/misc/user/0/cacerts-added",
                "cat << 'EOF' > $path\n$pem\nEOF",
                "chmod 644 $path",
                "chown system:system $path 2>/dev/null || true",
                "rm -f /data/misc/user/0/cacerts-removed/6572d38d.0",
            ),
            shell.batches.single(),
        )
    }

    @Test
    fun `system store install builds a module with both hashes and a user store copy`() {
        val sha1 = SystemCertificateInstaller.computeSubjectHashSha1(cert)
        assertEquals("6572d38d", SystemCertificateInstaller.installToSystemStore(pem).getOrThrow())
        val batch = shell.batches.single()
        val module = "/data/adb/modules/blockads_ca"
        for (hash in listOf("6572d38d", sha1)) {
            assertTrue(batch.contains("chmod 644 $module/system/etc/security/cacerts/$hash.0"))
            assertTrue(batch.contains("chmod 644 $module/system/apex/com.android.conscrypt/cacerts/$hash.0"))
        }
        assertTrue(batch.any { it.startsWith("cat << 'EOF' > $module/module.prop") && "id=blockads_ca" in it })
        assertTrue(batch.contains("chmod 755 $module/service.sh"))
        assertEquals("chown system:system /data/misc/user/0/cacerts-added/6572d38d.0 2>/dev/null || true", batch.last())
    }

    @Test
    fun `a failing shell surfaces its stderr`() {
        shell.respond = { FakeResult(isSuccess = false, err = listOf("read-only", "file system")) }
        val result = SystemCertificateInstaller.installToUserStoreViaRoot(pem)
        assertEquals("read-only\nfile system", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an unparseable PEM fails before any install command`() {
        assertTrue(SystemCertificateInstaller.installToSystemStore("not a cert").isFailure)
        assertFalse(shell.batches.isNotEmpty())
    }
}
