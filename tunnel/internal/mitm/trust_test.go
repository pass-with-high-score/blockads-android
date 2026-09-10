package mitm

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/pem"
	"math/big"
	"testing"
	"time"
)

// TestBundledRootsParse ensures the embedded ISRG roots are valid PEM
// and currently valid (not expired) — a typo in the base64 would make
// upstream verification silently weaker, so guard it in CI.
func TestBundledRootsParse(t *testing.T) {
	for name, pemStr := range map[string]string{
		"ISRG Root X1": isrgRootX1PEM,
		"ISRG Root X2": isrgRootX2PEM,
	} {
		block, _ := pem.Decode([]byte(pemStr))
		if block == nil {
			t.Fatalf("%s: no PEM block decoded", name)
		}
		cert, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			t.Fatalf("%s: parse: %v", name, err)
		}
		if !cert.IsCA {
			t.Errorf("%s: expected IsCA=true", name)
		}
		if time.Now().After(cert.NotAfter) {
			t.Errorf("%s: bundled root is expired (NotAfter=%s)", name, cert.NotAfter)
		}
	}
}

// TestUpstreamRootPoolIncludesBundled verifies the shared pool is built
// and includes the bundled roots on top of the system pool.
func TestUpstreamRootPoolIncludesBundled(t *testing.T) {
	pool := upstreamRootPool()
	if pool == nil {
		t.Fatal("upstreamRootPool returned nil")
	}
	// Subjects() is deprecated but remains the simplest way to assert the
	// bundled roots landed in the pool. Match on the ISRG O= RDN.
	found := 0
	for _, subj := range pool.Subjects() { //nolint:staticcheck
		var rdn pkix.RDNSequence
		if _, err := asn1.Unmarshal(subj, &rdn); err != nil {
			continue
		}
		var name pkix.Name
		name.FillFromRDNSequence(&rdn)
		if name.CommonName == "ISRG Root X1" || name.CommonName == "ISRG Root X2" {
			found++
		}
	}
	if found < 2 {
		t.Errorf("expected both bundled ISRG roots in pool, found %d", found)
	}
}

// TestIsExtendedValidation checks EV detection against a cert carrying
// the unified CA/B Forum EV policy OID, and a non-EV cert.
func TestIsExtendedValidation(t *testing.T) {
	mk := func(policies []asn1.ObjectIdentifier) *x509.Certificate {
		key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		tmpl := &x509.Certificate{
			SerialNumber:      big.NewInt(1),
			Subject:           pkix.Name{CommonName: "example.com"},
			NotBefore:         time.Now().Add(-time.Hour),
			NotAfter:          time.Now().Add(time.Hour),
			PolicyIdentifiers: policies,
		}
		der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
		if err != nil {
			t.Fatalf("create cert: %v", err)
		}
		c, err := x509.ParseCertificate(der)
		if err != nil {
			t.Fatalf("parse cert: %v", err)
		}
		return c
	}

	ev := mk([]asn1.ObjectIdentifier{{2, 23, 140, 1, 1}})
	if !isExtendedValidation(ev) {
		t.Error("expected EV cert to be detected")
	}

	dv := mk([]asn1.ObjectIdentifier{{2, 23, 140, 1, 2, 1}}) // domain-validated
	if isExtendedValidation(dv) {
		t.Error("DV cert should not be flagged EV")
	}

	if isExtendedValidation(nil) {
		t.Error("nil cert should not be flagged EV")
	}
}
