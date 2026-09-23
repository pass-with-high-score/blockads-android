package mitm

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// newTestCertManager builds a CertManager backed by a fresh temp dir.
func newTestCertManager(t *testing.T) (*CertManager, string) {
	t.Helper()
	dir := t.TempDir()
	cm, err := NewCertManager(dir)
	if err != nil {
		t.Fatalf("NewCertManager: %v", err)
	}
	return cm, dir
}

// writeCA writes a self-signed ECDSA CA with the given validity window and
// returns the cert and key PEM.
func writeCA(t *testing.T, dir string, isCA bool, notBefore, notAfter time.Time) ([]byte, []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(42),
		Subject:               pkix.Name{CommonName: "Test CA"},
		NotBefore:             notBefore,
		NotAfter:              notAfter,
		KeyUsage:              x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
		IsCA:                  isCA,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	mustWrite(t, filepath.Join(dir, CACertFile), certPEM)
	mustWrite(t, filepath.Join(dir, CAKeyFile), keyPEM)
	return certPEM, keyPEM
}

func mustWrite(t *testing.T, path string, b []byte) {
	t.Helper()
	if err := os.WriteFile(path, b, 0600); err != nil {
		t.Fatal(err)
	}
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestCAGenerateAndPersist(t *testing.T) {
	cm, dir := newTestCertManager(t)

	certPEM := mustRead(t, filepath.Join(dir, CACertFile))
	if got := cm.GetCACertPEM(); got != string(certPEM) {
		t.Fatalf("GetCACertPEM differs from the persisted cert")
	}
	info, err := os.Stat(filepath.Join(dir, CAKeyFile))
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0600 {
		t.Errorf("CA key perms = %o, want 0600", perm)
	}

	cm.mu.RLock()
	ca := cm.caCert
	cm.mu.RUnlock()
	if !ca.IsCA || ca.Subject.CommonName != "BlockAds Root CA" {
		t.Errorf("unexpected CA: IsCA=%v CN=%q", ca.IsCA, ca.Subject.CommonName)
	}
	if ca.KeyUsage&x509.KeyUsageCertSign == 0 {
		t.Error("CA lacks KeyUsageCertSign")
	}
	if ca.NotAfter.Before(time.Now().Add(9 * 365 * 24 * time.Hour)) {
		t.Errorf("CA NotAfter %s is shorter than ~10 years", ca.NotAfter)
	}
}

func TestCALoadRoundTrip(t *testing.T) {
	first, dir := newTestCertManager(t)
	keyBefore := mustRead(t, filepath.Join(dir, CAKeyFile))

	second, err := NewCertManager(dir)
	if err != nil {
		t.Fatalf("reload: %v", err)
	}
	if first.GetCACertPEM() != second.GetCACertPEM() {
		t.Fatal("reloaded CA differs from the generated one")
	}
	if !bytes.Equal(keyBefore, mustRead(t, filepath.Join(dir, CAKeyFile))) {
		t.Fatal("CA key rewritten on reload")
	}
	if first.caKey.D.Cmp(second.caKey.D) != 0 {
		t.Fatal("reloaded private key differs")
	}
}

func TestCALoadRejectsInvalid(t *testing.T) {
	now := time.Now()
	cases := map[string]func(t *testing.T, dir string){
		"key mismatch": func(t *testing.T, dir string) {
			writeCA(t, dir, true, now.Add(-time.Hour), now.Add(time.Hour))
			other := t.TempDir()
			_, otherKey := writeCA(t, other, true, now.Add(-time.Hour), now.Add(time.Hour))
			mustWrite(t, filepath.Join(dir, CAKeyFile), otherKey)
		},
		"expired": func(t *testing.T, dir string) {
			writeCA(t, dir, true, now.Add(-48*time.Hour), now.Add(-24*time.Hour))
		},
		"not yet valid": func(t *testing.T, dir string) {
			writeCA(t, dir, true, now.Add(24*time.Hour), now.Add(48*time.Hour))
		},
		"not a CA": func(t *testing.T, dir string) {
			writeCA(t, dir, false, now.Add(-time.Hour), now.Add(time.Hour))
		},
		"garbage cert": func(t *testing.T, dir string) {
			writeCA(t, dir, true, now.Add(-time.Hour), now.Add(time.Hour))
			mustWrite(t, filepath.Join(dir, CACertFile), []byte("not pem"))
		},
		"bad cert DER": func(t *testing.T, dir string) {
			writeCA(t, dir, true, now.Add(-time.Hour), now.Add(time.Hour))
			mustWrite(t, filepath.Join(dir, CACertFile), pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: []byte{1, 2, 3}}))
		},
		"garbage key": func(t *testing.T, dir string) {
			writeCA(t, dir, true, now.Add(-time.Hour), now.Add(time.Hour))
			mustWrite(t, filepath.Join(dir, CAKeyFile), []byte("not pem"))
		},
		"bad key DER": func(t *testing.T, dir string) {
			writeCA(t, dir, true, now.Add(-time.Hour), now.Add(time.Hour))
			mustWrite(t, filepath.Join(dir, CAKeyFile), pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: []byte{1, 2, 3}}))
		},
	}
	for name, setup := range cases {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			setup(t, dir)
			cm := &CertManager{}
			if err := cm.loadCA(filepath.Join(dir, CACertFile), filepath.Join(dir, CAKeyFile)); err == nil {
				t.Fatal("loadCA accepted an invalid CA")
			}
		})
	}
	t.Run("missing files", func(t *testing.T) {
		dir := t.TempDir()
		cm := &CertManager{}
		if err := cm.loadCA(filepath.Join(dir, "nope.crt"), filepath.Join(dir, "nope.key")); err == nil {
			t.Fatal("loadCA accepted a missing cert")
		}
		writeCA(t, dir, true, now.Add(-time.Hour), now.Add(time.Hour))
		if err := cm.loadCA(filepath.Join(dir, CACertFile), filepath.Join(dir, "nope.key")); err == nil {
			t.Fatal("loadCA accepted a missing key")
		}
	})
}

// An invalid on-disk CA is replaced by a new one today; the replacement is
// written over the files, which silently invalidates the CA the user
// installed. This characterizes the current fallback.
func TestInitCARegeneratesCorruptCA(t *testing.T) {
	dir := t.TempDir()
	mustWrite(t, filepath.Join(dir, CACertFile), []byte("junk"))
	mustWrite(t, filepath.Join(dir, CAKeyFile), []byte("junk"))
	cm, err := NewCertManager(dir)
	if err != nil {
		t.Fatalf("NewCertManager: %v", err)
	}
	if got := mustRead(t, filepath.Join(dir, CACertFile)); string(got) != cm.GetCACertPEM() {
		t.Fatal("regenerated CA was not persisted")
	}
}

// An expired or corrupt CA must not be silently regenerated over the
// installed one; the caller should get an error to surface to the user.
func TestInitCAExpiredIsNotSilentlyReplaced(t *testing.T) {
	t.Skip("known bug: expired/corrupt CA is silently regenerated and overwritten")
	dir := t.TempDir()
	now := time.Now()
	certPEM, _ := writeCA(t, dir, true, now.Add(-48*time.Hour), now.Add(-24*time.Hour))
	_, err := NewCertManager(dir)
	if err == nil {
		t.Error("NewCertManager returned nil error for an expired CA")
	}
	if !bytes.Equal(certPEM, mustRead(t, filepath.Join(dir, CACertFile))) {
		t.Error("expired CA was overwritten on disk")
	}
}

func TestInitCASaveFailureIsNonFatal(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "does-not-exist")
	cm, err := NewCertManager(dir)
	if err != nil {
		t.Fatalf("NewCertManager: %v", err)
	}
	if cm.GetCACertPEM() == "" {
		t.Fatal("in-memory CA missing after save failure")
	}
	if fileExists(filepath.Join(dir, CACertFile)) {
		t.Fatal("cert unexpectedly written")
	}
	if fileExists(dir) {
		t.Fatal("fileExists reported a missing directory as a file")
	}
}

func TestSaveCAKeyWriteFailure(t *testing.T) {
	cm, dir := newTestCertManager(t)
	keyDir := filepath.Join(dir, "keydir")
	if err := os.Mkdir(keyDir, 0700); err != nil {
		t.Fatal(err)
	}
	if err := cm.saveCA(filepath.Join(dir, "x.crt"), keyDir); err == nil {
		t.Fatal("saveCA succeeded writing the key over a directory")
	}
}

func TestLeafCertProperties(t *testing.T) {
	cm, _ := newTestCertManager(t)
	roots := x509.NewCertPool()
	roots.AddCert(cm.caCert)

	cases := []struct {
		host     string
		wantSANs []string
	}{
		{"www.example.com", []string{"www.example.com", "*.example.com"}},
		{"example.com", []string{"example.com"}},
		{"a.b.example.co.uk", []string{"a.b.example.co.uk", "*.b.example.co.uk"}},
	}
	for _, tc := range cases {
		t.Run(tc.host, func(t *testing.T) {
			tlsCert, err := cm.getCertForHost(tc.host)
			if err != nil {
				t.Fatal(err)
			}
			if len(tlsCert.Certificate) != 2 {
				t.Fatalf("chain length = %d, want leaf+CA", len(tlsCert.Certificate))
			}
			leaf, err := x509.ParseCertificate(tlsCert.Certificate[0])
			if err != nil {
				t.Fatal(err)
			}
			if fmt.Sprint(leaf.DNSNames) != fmt.Sprint(tc.wantSANs) {
				t.Errorf("DNSNames = %v, want %v", leaf.DNSNames, tc.wantSANs)
			}
			if leaf.IsCA {
				t.Error("leaf is a CA")
			}
			if leaf.Subject.CommonName != tc.host {
				t.Errorf("CN = %q", leaf.Subject.CommonName)
			}
			if d := leaf.NotAfter.Sub(time.Now()); d > 25*time.Hour || d < 23*time.Hour {
				t.Errorf("leaf lifetime %s, want ~24h", d)
			}
			if _, err := leaf.Verify(x509.VerifyOptions{DNSName: tc.host, Roots: roots}); err != nil {
				t.Errorf("leaf does not verify against the CA: %v", err)
			}
			again, err := cm.getCertForHost(tc.host)
			if err != nil || again != tlsCert {
				t.Error("second lookup did not hit the cache")
			}
		})
	}
}

// When SNI is missing the handler falls back to the server IP, which
// lands in DNSNames instead of IPAddresses, so browsers reject the leaf.
func TestLeafCertForIPUsesIPSAN(t *testing.T) {
	t.Skip("known bug: IP hostnames are minted as DNSNames, not IPAddresses")
	cm, _ := newTestCertManager(t)
	tlsCert, err := cm.getCertForHost("93.184.216.34")
	if err != nil {
		t.Fatal(err)
	}
	leaf, _ := x509.ParseCertificate(tlsCert.Certificate[0])
	if len(leaf.IPAddresses) != 1 || !leaf.IPAddresses[0].Equal(net.ParseIP("93.184.216.34")) {
		t.Errorf("IPAddresses = %v, DNSNames = %v", leaf.IPAddresses, leaf.DNSNames)
	}
}

func TestLeafCacheEvictsNearExpiry(t *testing.T) {
	cm, _ := newTestCertManager(t)
	first, err := cm.getCertificateWithDedup("stale.example.com")
	if err != nil {
		t.Fatal(err)
	}
	// Age the cached entry into the 5-minute renewal window.
	cm.certCache.Store("stale.example.com", &cachedCert{cert: first, expiresAt: time.Now().Add(time.Minute)})
	second, err := cm.getCertificateWithDedup("stale.example.com")
	if err != nil {
		t.Fatal(err)
	}
	if second == first {
		t.Fatal("near-expiry entry was served from cache")
	}
	cm.certCache.Store("stale.example.com", &cachedCert{cert: first, expiresAt: time.Now().Add(time.Minute)})
	third, err := cm.getCertForHost("stale.example.com")
	if err != nil || third == first {
		t.Fatalf("getCertForHost served a near-expiry entry (err=%v)", err)
	}
}

func TestGetCertWithoutCA(t *testing.T) {
	cm := &CertManager{}
	if _, err := cm.getCertForHost("example.com"); err == nil {
		t.Fatal("expected error without a CA")
	}
	if _, err := cm.getCertificateWithDedup("example.com"); err == nil {
		t.Fatal("expected error without a CA")
	}
}

func TestGetCertificateWithDedupConcurrent(t *testing.T) {
	cm, _ := newTestCertManager(t)
	const n = 32
	certs := make([]*tls.Certificate, n)
	errs := make([]error, n)
	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			certs[i], errs[i] = cm.getCertificateWithDedup("dedup.example.com")
		}(i)
	}
	close(start)
	wg.Wait()
	for i := range certs {
		if errs[i] != nil {
			t.Fatalf("goroutine %d: %v", i, errs[i])
		}
		if certs[i] != certs[0] {
			t.Fatalf("goroutine %d got a different cert; dedup failed", i)
		}
	}
	if _, inFlight := cm.flightCache.Load("dedup.example.com"); inFlight {
		t.Fatal("flightCache entry leaked")
	}
}

// Waiters of a failed generation get an error instead of hanging.
func TestGetCertificateWithDedupWaiterFailure(t *testing.T) {
	cm := &CertManager{}
	wg := &sync.WaitGroup{}
	wg.Add(1)
	cm.flightCache.Store("fail.example.com", wg)
	done := make(chan error, 1)
	go func() {
		_, err := cm.getCertificateWithDedup("fail.example.com")
		done <- err
	}()
	time.Sleep(10 * time.Millisecond)
	wg.Done()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("waiter got nil error after the leader failed")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("waiter hung")
	}
}

// certCache has no size bound, so one leaf per visited host lives for 24h.
func TestCertCacheIsBounded(t *testing.T) {
	t.Skip("known bug: certCache grows without bound")
	if testing.Short() {
		t.Skip("slow")
	}
	cm, _ := newTestCertManager(t)
	for i := 0; i < 5000; i++ {
		if _, err := cm.getCertForHost(fmt.Sprintf("h%d.example.com", i)); err != nil {
			t.Fatal(err)
		}
	}
	n := 0
	cm.certCache.Range(func(_, _ any) bool { n++; return true })
	if n > 1024 {
		t.Errorf("certCache holds %d entries", n)
	}
}

func TestDynamicTLSConfigHandshake(t *testing.T) {
	cm, _ := newTestCertManager(t)
	roots := x509.NewCertPool()
	roots.AddCert(cm.caCert)

	for _, tc := range []struct{ name, sni, fallback, verify string }{
		{"sni", "sni.example.com", "fallback.example.com", "sni.example.com"},
		{"no sni", "", "fallback.example.com", "fallback.example.com"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c, s := net.Pipe()
			defer c.Close()
			defer s.Close()
			srv := tls.Server(s, cm.GetDynamicTLSConfigForHost(tc.fallback))
			srvErr := make(chan error, 1)
			go func() { srvErr <- srv.Handshake() }()

			var peer *x509.Certificate
			cli := tls.Client(c, &tls.Config{
				ServerName:         tc.sni,
				InsecureSkipVerify: true, // verified manually below (no SNI case)
				NextProtos:         []string{"h2", "http/1.1"},
				VerifyConnection: func(cs tls.ConnectionState) error {
					peer = cs.PeerCertificates[0]
					return nil
				},
			})
			if err := cli.Handshake(); err != nil {
				t.Fatalf("client handshake: %v", err)
			}
			if err := <-srvErr; err != nil {
				t.Fatalf("server handshake: %v", err)
			}
			if p := cli.ConnectionState().NegotiatedProtocol; p != "http/1.1" {
				t.Errorf("ALPN = %q, want http/1.1", p)
			}
			if _, err := peer.Verify(x509.VerifyOptions{DNSName: tc.verify, Roots: roots}); err != nil {
				t.Errorf("leaf verify for %s: %v", tc.verify, err)
			}
		})
	}
}

func TestWarmLocalAssetCert(t *testing.T) {
	cm, _ := newTestCertManager(t)
	cm.WarmLocalAssetCert()
	if _, ok := cm.certCache.Load(LocalAssetHost); !ok {
		t.Fatal("local asset cert not cached")
	}
	(&CertManager{}).WarmLocalAssetCert() // logs, must not panic
	SetLocalAssetCertManager(nil)
}
