package tunnel

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ─────────────────────────────────────────────────────────────────────────────
// MITM Certificate Authority — Root CA generation and per-domain cert minting.
//
// • initCA(certDir) loads ca.crt/ca.key from disk if they exist,
//   otherwise generates a new pair, writes them to disk, and uses them.
// • getCertForHost(host) generates a leaf cert signed by the CA on demand.
// • An in-memory cache avoids regenerating certs for the same domain.
//
// ECDSA P-256 is used instead of RSA-2048 because:
//   - Faster key generation (important on Android: ~1ms vs ~100ms)
//   - Smaller certs = less memory per cached entry
//   - Same security level as RSA-3072
// ─────────────────────────────────────────────────────────────────────────────

const (
	caCertFile = "ca.crt"
	caKeyFile  = "ca.key"
)

// CertManager handles Root CA lifecycle and per-host certificate generation.
type CertManager struct {
	mu      sync.RWMutex
	caCert  *x509.Certificate
	caKey   *ecdsa.PrivateKey
	caPEM   []byte // PEM-encoded CA cert for export to Android
	caKeyPEM []byte

	// Per-host cert cache (host → *cachedCert)
	certCache sync.Map

	// flightCache deduplicates concurrent cert generation requests (host → *sync.WaitGroup)
	flightCache sync.Map
}

// cachedCert wraps a TLS certificate with its expiry time for TTL eviction.
type cachedCert struct {
	cert      *tls.Certificate
	expiresAt time.Time
}

// NewCertManager creates a CertManager and initialises the Root CA.
// certDir is the persistent directory (e.g., Android's getFilesDir()).
//   - If ca.crt and ca.key exist in certDir, they are loaded.
//   - Otherwise a fresh Root CA is generated once and saved to certDir.
func NewCertManager(certDir string) (*CertManager, error) {
	cm := &CertManager{}
	if err := cm.initCA(certDir); err != nil {
		return nil, err
	}
	return cm, nil
}

// GetCACertPEM returns the PEM-encoded Root CA certificate.
// The user must install this on their Android device:
//   Settings → Security → Encryption & credentials → Install from storage
func (cm *CertManager) GetCACertPEM() string {
	cm.mu.RLock()
	defer cm.mu.RUnlock()
	return string(cm.caPEM)
}

// GetDynamicTLSConfigForHost returns a *tls.Config configured with a
// GetCertificate callback. This allows the TLS server to dynamically
// fetch or generate an ECDSA certificate exactly when the handshake begins.
// It captures the defaultHost from the CONNECT request as a fallback in case
// the client does not send SNI (Server Name Indication).
func (cm *CertManager) GetDynamicTLSConfigForHost(defaultHost string) *tls.Config {
	return &tls.Config{
		GetCertificate: func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
			host := hello.ServerName
			if host == "" {
				host = defaultHost
			}
			return cm.getCertificateWithDedup(host)
		},
	}
}

// getCertificateWithDedup provides fast-path caching and Singleflight deduplication
func (cm *CertManager) getCertificateWithDedup(host string) (*tls.Certificate, error) {
	// ── FAST PATH: Cache hit (with TTL check) ───────────────────────────
	if cached, ok := cm.certCache.Load(host); ok {
		entry := cached.(*cachedCert)
		// Evict if cert expires within 5 minutes (renew early to avoid serving stale)
		if time.Now().Before(entry.expiresAt.Add(-5 * time.Minute)) {
			return entry.cert, nil
		}
		// Expired or about to expire — delete and regenerate below
		cm.certCache.Delete(host)
	}

	// ── SLOW PATH: Deduplicate and Generate ─────────────────────────────────
	// Check if another goroutine is already generating a cert for this host.
	// If so, wait for it to finish and use its result.
	wg := &sync.WaitGroup{}
	wg.Add(1)
	actual, loaded := cm.flightCache.LoadOrStore(host, wg)
	
	if loaded {
		// Another goroutine won the race to generate the cert.
		// Wait for it to finish.
		actual.(*sync.WaitGroup).Wait()
		
		// The cert should now be in the main certCache.
		if cached, ok := cm.certCache.Load(host); ok {
			return cached.(*tls.Certificate), nil
		}
		// If it's not in the cache, the other goroutine failed.
		// We could retry, but for simplicity we fall through and fail here.
		return nil, fmt.Errorf("concurrent certificate generation failed for %s", host)
	}

	// We won the race. We must generate the cert, cache it, and unblock others.
	defer func() {
		cm.flightCache.Delete(host)
		wg.Done()
	}()

	return cm.getCertForHost(host)
}

// ── Internal ─────────────────────────────────────────────────────────────────

// initCA loads an existing CA from certDir, or generates a new one and
// persists it to certDir so subsequent starts reuse the same Root CA.
func (cm *CertManager) initCA(certDir string) error {
	certPath := filepath.Join(certDir, caCertFile)
	keyPath := filepath.Join(certDir, caKeyFile)

	// ── Attempt to load existing CA from disk ──────────────────────
	if fileExists(certPath) && fileExists(keyPath) {
		if err := cm.loadCA(certPath, keyPath); err == nil {
			logf("MITM CA loaded from disk: %s", certDir)
			return nil
		}
		// If loading fails (corrupt file, etc.), fall through and regenerate.
		logf("MITM CA: failed to load from disk, regenerating...")
	}

	// ── Generate a new CA and write it to disk ─────────────────────
	if err := cm.generateCA(); err != nil {
		return err
	}
	if err := cm.saveCA(certPath, keyPath); err != nil {
		// Non-fatal: the proxy can still work with the in-memory CA,
		// but the next restart will generate a new one.
		logf("MITM CA: WARNING — failed to save to disk: %v", err)
	} else {
		logf("MITM CA: generated and saved to %s", certDir)
	}
	return nil
}

// loadCA reads PEM-encoded cert and key from disk and parses them.
// It validates the cert is a CA, is not expired, and the key matches.
func (cm *CertManager) loadCA(certPath, keyPath string) error {
	certPEM, err := os.ReadFile(certPath)
	if err != nil {
		return fmt.Errorf("read CA cert: %w", err)
	}
	keyPEM, err := os.ReadFile(keyPath)
	if err != nil {
		return fmt.Errorf("read CA key: %w", err)
	}

	// Parse certificate
	certBlock, _ := pem.Decode(certPEM)
	if certBlock == nil {
		return fmt.Errorf("decode CA cert PEM: no PEM block found")
	}
	caCert, err := x509.ParseCertificate(certBlock.Bytes)
	if err != nil {
		return fmt.Errorf("parse CA cert: %w", err)
	}

	// Validate: must be a CA certificate
	if !caCert.IsCA {
		return fmt.Errorf("loaded certificate is not a CA (IsCA=false)")
	}

	// Validate: must not be expired
	now := time.Now()
	if now.Before(caCert.NotBefore) || now.After(caCert.NotAfter) {
		return fmt.Errorf("CA certificate expired or not yet valid (NotBefore=%s, NotAfter=%s)",
			caCert.NotBefore.Format("2006-01-02"), caCert.NotAfter.Format("2006-01-02"))
	}

	// Parse private key
	keyBlock, _ := pem.Decode(keyPEM)
	if keyBlock == nil {
		return fmt.Errorf("decode CA key PEM: no PEM block found")
	}
	caKey, err := x509.ParseECPrivateKey(keyBlock.Bytes)
	if err != nil {
		return fmt.Errorf("parse CA key: %w", err)
	}

	// Validate: private key must match the certificate's public key
	certPubKey, ok := caCert.PublicKey.(*ecdsa.PublicKey)
	if !ok {
		return fmt.Errorf("CA certificate public key is not ECDSA")
	}
	if caKey.PublicKey.X.Cmp(certPubKey.X) != 0 || caKey.PublicKey.Y.Cmp(certPubKey.Y) != 0 {
		return fmt.Errorf("CA private key does not match certificate public key")
	}

	cm.mu.Lock()
	cm.caCert = caCert
	cm.caKey = caKey
	cm.caPEM = certPEM
	cm.caKeyPEM = keyPEM
	cm.mu.Unlock()

	logf("MITM CA loaded: CN=%s, valid until %s", caCert.Subject.CommonName, caCert.NotAfter.Format("2006-01-02"))
	return nil
}

// saveCA writes the in-memory CA cert and key to disk as PEM files.
func (cm *CertManager) saveCA(certPath, keyPath string) error {
	cm.mu.RLock()
	certPEM := cm.caPEM
	keyPEM := cm.caKeyPEM
	cm.mu.RUnlock()

	// Write cert (world-readable is fine — it's a public certificate)
	if err := os.WriteFile(certPath, certPEM, 0644); err != nil {
		return fmt.Errorf("write CA cert: %w", err)
	}
	// Write key (owner-only — private key must stay secret)
	if err := os.WriteFile(keyPath, keyPEM, 0600); err != nil {
		return fmt.Errorf("write CA key: %w", err)
	}
	return nil
}

// generateCA creates a self-signed ECDSA P-256 Root CA.
func (cm *CertManager) generateCA() error {
	// Generate CA private key
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("generate CA key: %w", err)
	}

	// Serial number
	serialNumber, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return fmt.Errorf("generate serial: %w", err)
	}

	// CA certificate template
	caTemplate := &x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			Organization: []string{"BlockAds"},
			CommonName:   "BlockAds Root CA",
		},
		NotBefore:             time.Now().Add(-24 * time.Hour), // 1 day grace
		NotAfter:              time.Now().Add(10 * 365 * 24 * time.Hour), // 10 years
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLen:            1,
	}

	// Self-sign
	caCertDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		return fmt.Errorf("create CA cert: %w", err)
	}

	// Parse back to x509.Certificate
	caCert, err := x509.ParseCertificate(caCertDER)
	if err != nil {
		return fmt.Errorf("parse CA cert: %w", err)
	}

	// Encode to PEM
	caPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caCertDER})

	caKeyDER, err := x509.MarshalECPrivateKey(caKey)
	if err != nil {
		return fmt.Errorf("marshal CA key: %w", err)
	}
	caKeyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: caKeyDER})

	cm.mu.Lock()
	cm.caCert = caCert
	cm.caKey = caKey
	cm.caPEM = caPEM
	cm.caKeyPEM = caKeyPEM
	cm.mu.Unlock()

	logf("MITM CA generated: CN=%s, valid until %s", caCert.Subject.CommonName, caCert.NotAfter.Format("2006-01-02"))
	return nil
}

// getCertForHost returns a cached or freshly-generated TLS certificate
// for the given hostname, signed by the Root CA.
func (cm *CertManager) getCertForHost(host string) (*tls.Certificate, error) {
	// Check cache first (with TTL)
	if cached, ok := cm.certCache.Load(host); ok {
		entry := cached.(*cachedCert)
		if time.Now().Before(entry.expiresAt.Add(-5 * time.Minute)) {
			return entry.cert, nil
		}
		cm.certCache.Delete(host)
	}

	// Generate new leaf cert
	cm.mu.RLock()
	caCert := cm.caCert
	caKey := cm.caKey
	cm.mu.RUnlock()

	if caCert == nil || caKey == nil {
		return nil, fmt.Errorf("CA not initialized")
	}

	// Leaf key (ECDSA P-256 — fast generation)
	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate leaf key for %s: %w", host, err)
	}

	serialNumber, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("generate serial for %s: %w", host, err)
	}

	// Build SANs: exact host + wildcard for subdomains.
	// e.g., host="www.example.com" → DNSNames=["www.example.com", "*.example.com"]
	// This prevents Chrome NET::ERR_CERT_COMMON_NAME_INVALID for sub-resources.
	dnsNames := []string{host}
	if parts := strings.SplitN(host, ".", 2); len(parts) == 2 && strings.Contains(parts[1], ".") {
		wildcard := "*." + parts[1]
		dnsNames = append(dnsNames, wildcard)
	}

	leafTemplate := &x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			Organization: []string{"BlockAds"},
			CommonName:   host,
		},
		DNSNames:              dnsNames,
		NotBefore:             time.Now().Add(-1 * time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour), // Short-lived (24h)
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}

	// Sign with CA
	leafCertDER, err := x509.CreateCertificate(rand.Reader, leafTemplate, caCert, &leafKey.PublicKey, caKey)
	if err != nil {
		return nil, fmt.Errorf("sign leaf cert for %s: %w", host, err)
	}

	tlsCert := &tls.Certificate{
		Certificate: [][]byte{leafCertDER, caCert.Raw},
		PrivateKey:  leafKey,
	}

	// Cache with TTL (matches the cert's NotAfter)
	cm.certCache.Store(host, &cachedCert{
		cert:      tlsCert,
		expiresAt: leafTemplate.NotAfter,
	})
	return tlsCert, nil
}

// fileExists returns true if the path exists and is a regular file.
func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}
