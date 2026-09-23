package dns

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
	"net"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	miekgdns "github.com/miekg/dns"
)

// testCert is a self-signed certificate for 127.0.0.1/localhost. TestMain
// points SSL_CERT_FILE at it before anything loads the system roots, so the
// production DoT, DoQ and DoH code paths (which use the system pool) trust
// the local test servers without a production seam.
var (
	testCert    tls.Certificate
	trustedRoot bool // false when the platform ignores SSL_CERT_FILE
)

func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "dns-test-ca")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	var certPEM []byte
	testCert, certPEM, err = selfSigned()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	path := filepath.Join(dir, "ca.pem")
	if err := os.WriteFile(path, certPEM, 0o600); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	os.Setenv("SSL_CERT_FILE", path)
	os.Setenv("SSL_CERT_DIR", dir)

	leaf, _ := x509.ParseCertificate(testCert.Certificate[0])
	_, verr := leaf.Verify(x509.VerifyOptions{DNSName: "127.0.0.1"})
	trustedRoot = verr == nil

	code := m.Run()
	os.RemoveAll(dir)
	os.Exit(code)
}

// selfSigned makes a fresh self-signed CA certificate valid for 127.0.0.1
// and localhost. Each call uses a new key, so only the one TestMain installs
// is trusted.
func selfSigned() (tls.Certificate, []byte, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "blockads dns test"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IPAddresses:           []net.IP{net.IPv4(127, 0, 0, 1)},
		DNSNames:              []string{"localhost"},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, nil, err
	}
	cert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
	return cert, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), nil
}

func requireTrustedRoot(t *testing.T) {
	t.Helper()
	if !trustedRoot {
		t.Skip("platform ignores SSL_CERT_FILE; cannot trust the local test CA")
	}
}

// query builds a packed A query for name.
func query(t testing.TB, name string, qtype uint16) []byte {
	t.Helper()
	m := new(miekgdns.Msg)
	m.SetQuestion(miekgdns.Fqdn(name), qtype)
	m.RecursionDesired = true
	raw, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

// answerA replies to any question with a single A record.
func answerA(ip string) func(*miekgdns.Msg) *miekgdns.Msg {
	return func(req *miekgdns.Msg) *miekgdns.Msg {
		m := new(miekgdns.Msg)
		m.SetReply(req)
		if len(req.Question) > 0 {
			rr, _ := miekgdns.NewRR(req.Question[0].Name + " 60 IN A " + ip)
			m.Answer = append(m.Answer, rr)
		}
		return m
	}
}

func unpack(t testing.TB, raw []byte) *miekgdns.Msg {
	t.Helper()
	var m miekgdns.Msg
	if err := m.Unpack(raw); err != nil {
		t.Fatalf("unpack response: %v", err)
	}
	return &m
}

func firstA(t testing.TB, raw []byte) string {
	t.Helper()
	m := unpack(t, raw)
	for _, rr := range m.Answer {
		if a, ok := rr.(*miekgdns.A); ok {
			return a.A.String()
		}
	}
	t.Fatalf("no A record in %v", m)
	return ""
}

// udpServer is a local miekg UDP server that counts the queries it serves.
type udpServer struct {
	addr  string
	count atomic.Int64
}

func startUDP(t *testing.T, reply func(*miekgdns.Msg) *miekgdns.Msg) *udpServer {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &udpServer{addr: pc.LocalAddr().String()}
	started := make(chan struct{})
	srv := &miekgdns.Server{
		PacketConn:        pc,
		NotifyStartedFunc: func() { close(started) },
		Handler: miekgdns.HandlerFunc(func(w miekgdns.ResponseWriter, r *miekgdns.Msg) {
			s.count.Add(1)
			if resp := reply(r); resp != nil {
				_ = w.WriteMsg(resp)
			}
		}),
	}
	go func() { _ = srv.ActivateAndServe() }()
	<-started
	t.Cleanup(func() { _ = srv.Shutdown() })
	return s
}

// rawUDPServer answers each datagram with whatever respond returns, sent
// from the socket respond chooses (to simulate off-path spoofing).
func rawUDPServer(t *testing.T, respond func(pc net.PacketConn, from net.Addr, req []byte)) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 4096)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			respond(pc, from, append([]byte(nil), buf[:n]...))
		}
	}()
	return pc.LocalAddr().String()
}

// closedTCPAddr returns a loopback address with nothing listening on it.
func closedTCPAddr(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	l.Close()
	return addr
}

// openFDs counts this process's open file descriptors (Linux only).
func openFDs(t *testing.T) int {
	t.Helper()
	ents, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Skip("no /proc/self/fd on this platform")
	}
	return len(ents)
}
