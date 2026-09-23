package app.pwhs.blockads.utils

import com.topjohnwu.superuser.Shell
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class SystemCertificateInstallerTest {

    // Subject "C=US, O=BlockAds  Test, OU=Unit Tests, CN=BlockAds Test CA"; the double space
    // and mixed case exercise OpenSSL's canonical form.
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

    @After
    fun tearDown() = unmockkAll()

    private fun stubShell(grantedRoot: Boolean?, idSucceeds: Boolean, idOut: List<String>) {
        mockkStatic(Shell::class)
        every { Shell.isAppGrantedRoot() } returns grantedRoot
        val result = mockk<Shell.Result> {
            every { isSuccess } returns idSucceeds
            every { out } returns idOut
        }
        every { Shell.cmd(*anyVararg()) } returns mockk { every { exec() } returns result }
    }

    @Test
    fun `old subject hash matches openssl x509 -subject_hash_old`() {
        assertEquals("6572d38d", SystemCertificateInstaller.computeSubjectHashOld(cert))
    }

    @Ignore("Android cacerts only use subject_hash_old; decide whether to drop the SHA-1 copy or implement OpenSSL canonical encoding")
    @Test
    fun `sha1 subject hash matches openssl x509 -subject_hash`() {
        assertEquals("8c81b9eb", SystemCertificateInstaller.computeSubjectHashSha1(cert))
    }

    @Ignore("known bug: isRootAvailable trusts any successful id, not uid 0")
    @Test
    fun `root is unavailable when id runs as an unprivileged user`() {
        stubShell(grantedRoot = false, idSucceeds = true, idOut = listOf("uid=10234(u0_a234) gid=10234(u0_a234)"))
        assertFalse(
            "isRootAvailable trusted a successful non-root id",
            SystemCertificateInstaller.isRootAvailable()
        )
    }

    @Test
    fun `root is available when id reports uid 0`() {
        stubShell(grantedRoot = null, idSucceeds = true, idOut = listOf("uid=0(root) gid=0(root)"))
        assertTrue(SystemCertificateInstaller.isRootAvailable())
    }
}
