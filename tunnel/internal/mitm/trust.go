package mitm

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/asn1"
	"sync"
)

// ─────────────────────────────────────────────────────────────────────────────
// mitm_trust.go — Upstream trust store + high-security-cert detection.
//
// When the MITM handler re-establishes TLS to the *real* server it must
// verify that server's certificate (we are the client). Go's default
// verification uses crypto/x509.SystemCertPool(), which on Android reads
// /system/etc/security/cacerts (and the Conscrypt apex dir). On many
// devices that store is missing newer roots — most importantly ISRG
// Root X1 / X2 (Let's Encrypt), which back a huge fraction of the web.
// When the upstream root is missing, serverConn.Handshake() fails, the
// handler falls back to raw passthrough, and HTTPS filtering silently
// does nothing for those sites.
//
// AdGuard solves this exact problem the same way: its upstream trust
// store is "all system CAs + a hard-coded ISRG Root X1 PEM"
// (ProxyUtils.getCertificates). We mirror that: system pool ∪ bundled
// modern roots, built once and reused for every upstream dial.
//
// This file also carries the proactive "should we even MITM this?"
// checks AdGuard performs against the real server cert (EV certificates
// are left un-intercepted) so we don't break high-assurance sites on the
// first visit.
// ─────────────────────────────────────────────────────────────────────────────

// Bundled modern roots — appended to the system pool so upstream
// verification succeeds even on devices whose system store predates
// them. These are public CA roots, not secrets.
const (
	// ISRG Root X1 (Let's Encrypt) — valid until 2035-06-04.
	isrgRootX1PEM = `-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----`

	// ISRG Root X2 (Let's Encrypt, ECDSA) — valid until 2040-09-17.
	isrgRootX2PEM = `-----BEGIN CERTIFICATE-----
MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw
CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg
R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00
MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT
ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw
EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW
+1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9
ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI
zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW
tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1
/q4AaOeMSQ+2b1tbFfLn
-----END CERTIFICATE-----`
)

var (
	upstreamPoolOnce sync.Once
	upstreamPool     *x509.CertPool
)

// upstreamRootPool returns the shared trust store for verifying real
// upstream servers: the OS system pool plus the bundled modern roots.
// Built once and cached. If SystemCertPool() fails (rare), we start
// from an empty pool and rely on the bundled roots — still better than
// no verification.
func upstreamRootPool() *x509.CertPool {
	upstreamPoolOnce.Do(func() {
		pool, err := x509.SystemCertPool()
		if err != nil || pool == nil {
			logf("MITM trust: SystemCertPool unavailable (%v); using bundled roots only", err)
			pool = x509.NewCertPool()
		}
		added := 0
		for _, pem := range []string{isrgRootX1PEM, isrgRootX2PEM} {
			if pool.AppendCertsFromPEM([]byte(pem)) {
				added++
			}
		}
		logf("MITM trust: upstream root pool ready (bundled roots added=%d)", added)
		upstreamPool = pool
	})
	return upstreamPool
}

// upstreamTLSConfig builds the *tls.Config used when the MITM handler
// dials the real server as a TLS client. It uses the shared root pool
// and pins TLS 1.2 as the floor. clientCertRequested, when non-nil, is
// set to true if the server asks us for a client certificate — the
// signal AdGuard uses ("certificate_required ... will not filter") to
// bail out of MITM for mutual-TLS sites.
func upstreamTLSConfig(serverName string, clientCertRequested *bool) *tls.Config {
	return &tls.Config{
		ServerName: serverName,
		RootCAs:    upstreamRootPool(),
		MinVersion: tls.VersionTLS12,
		// The server requesting a client certificate means this is an
		// mTLS endpoint (corporate SSO, banking, smartcard auth). We
		// hold no client cert; presenting our MITM cert would break the
		// site. Record the request so the caller can passthrough.
		GetClientCertificate: func(req *tls.CertificateRequestInfo) (*tls.Certificate, error) {
			if clientCertRequested != nil {
				*clientCertRequested = true
			}
			// Return an empty certificate — we have none to offer.
			return &tls.Certificate{}, nil
		},
	}
}

// evPolicyOIDs are certificate-policy OIDs that indicate an Extended
// Validation certificate. The CA/Browser Forum assigns each EV-issuing
// CA its own arc; enumerating them all is impractical, but the modern
// unified "extended-validation" policy identifier plus the most common
// legacy arcs cover the overwhelming majority of EV certs seen in the
// wild. AdGuard leaves EV sites un-intercepted by default
// ("Not filtering this TLS connection because '{}' has EV certificate").
var evPolicyOIDs = []asn1.ObjectIdentifier{
	{2, 23, 140, 1, 1},              // CA/B Forum — extended-validation (unified)
	{1, 3, 6, 1, 4, 1, 6449, 1, 2, 1, 5, 1}, // Sectigo/Comodo EV
	{2, 16, 840, 1, 114412, 2, 1},   // DigiCert EV
	{2, 16, 840, 1, 114404, 1, 1, 2, 4, 1}, // Entrust EV
	{1, 3, 6, 1, 4, 1, 14370, 1, 6}, // GeoTrust EV
	{2, 16, 840, 1, 113733, 1, 7, 23, 6}, // VeriSign/Symantec EV
	{1, 3, 6, 1, 4, 1, 4146, 1, 1},  // GlobalSign EV
	{1, 3, 6, 1, 4, 1, 34697, 2, 1}, // Amazon Trust EV (legacy)
}

// isExtendedValidation reports whether cert carries an EV policy OID.
func isExtendedValidation(cert *x509.Certificate) bool {
	if cert == nil {
		return false
	}
	for _, p := range cert.PolicyIdentifiers {
		for _, ev := range evPolicyOIDs {
			if p.Equal(ev) {
				return true
			}
		}
	}
	return false
}
