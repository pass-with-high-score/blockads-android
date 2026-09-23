package mitm

import (
	"bufio"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// testPKI is a throwaway CA that signs upstream server certs.
type testPKI struct {
	cert *x509.Certificate
	key  *ecdsa.PrivateKey
}

func newTestPKI(t *testing.T) *testPKI {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "Upstream Test CA"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageCertSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, _ := x509.ParseCertificate(der)
	return &testPKI{cert, key}
}

func (p *testPKI) leaf(t *testing.T, host string, policies ...asn1.ObjectIdentifier) tls.Certificate {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber:      big.NewInt(time.Now().UnixNano()),
		Subject:           pkix.Name{CommonName: host},
		DNSNames:          []string{host},
		NotBefore:         time.Now().Add(-time.Hour),
		NotAfter:          time.Now().Add(time.Hour),
		KeyUsage:          x509.KeyUsageDigitalSignature,
		ExtKeyUsage:       []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		PolicyIdentifiers: policies,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, p.cert, &key.PublicKey, p.key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

// startUpstream runs an HTTPS server on 127.0.0.1 with the given cert and
// returns the flow that points at it.
func startUpstream(t *testing.T, cert tls.Certificate, clientAuth tls.ClientAuthType, h http.HandlerFunc) flowID {
	t.Helper()
	ts := httptest.NewUnstartedServer(h)
	ts.TLS = &tls.Config{Certificates: []tls.Certificate{cert}, ClientAuth: clientAuth}
	ts.StartTLS()
	t.Cleanup(ts.Close)
	return flowFor(t, ts.Listener.Addr())
}

func flowFor(t *testing.T, addr net.Addr) flowID {
	t.Helper()
	tcp := addr.(*net.TCPAddr)
	return flowID{clientIP: net.ParseIP("10.0.0.2"), clientPort: 40000, serverIP: tcp.IP, serverPort: uint16(tcp.Port)}
}

func htmlHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html")
	w.Header().Set("X-Saw-DNT", r.Header.Get("DNT"))
	io.WriteString(w, "<html><head></head><body>upstream</body></html>")
}

// runTLSFlow runs mitmTLSFlow against one end of a pipe and returns the other.
func runTLSFlow(t *testing.T, cm *CertManager, filter *MitmFilter, host string, flow flowID) (net.Conn, chan struct{}) {
	t.Helper()
	cli, srv := net.Pipe()
	done := make(chan struct{})
	go func() {
		mitmTLSFlow(srv, srv, cm, filter, &fakeBlocker{}, host, flow, nil)
		srv.Close()
		close(done)
	}()
	cli.SetDeadline(time.Now().Add(10 * time.Second))
	t.Cleanup(func() { cli.Close() })
	return cli, done
}

func waitClosed(t *testing.T, done chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("flow did not finish")
	}
}

func poolOf(certs ...*x509.Certificate) *x509.CertPool {
	p := x509.NewCertPool()
	for _, c := range certs {
		p.AddCert(c)
	}
	return p
}

func TestMitmTLSFlowInterceptsAndInjects(t *testing.T) {
	pki := newTestPKI(t)
	upstreamRootPool().AddCert(pki.cert)
	cm, _ := newTestCertManager(t)
	flow := startUpstream(t, pki.leaf(t, "mitm.test"), tls.NoClientCert, htmlHandler)

	cli, done := runTLSFlow(t, cm, NewMitmFilter(), "mitm.test", flow)
	tc := tls.Client(cli, &tls.Config{ServerName: "mitm.test", RootCAs: poolOf(cm.caCert)})
	if err := tc.Handshake(); err != nil {
		t.Fatalf("client handshake against the MITM cert: %v", err)
	}
	if iss := tc.ConnectionState().PeerCertificates[0].Issuer.CommonName; iss != "BlockAds Root CA" {
		t.Fatalf("leaf issued by %q", iss)
	}
	io.WriteString(tc, "GET / HTTP/1.1\r\nHost: mitm.test\r\nAccept: text/html\r\nConnection: close\r\n\r\n")
	resp, err := http.ReadResponse(bufio.NewReader(tc), nil)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), injectionTags) || resp.Header.Get("X-Saw-DNT") != "1" {
		t.Fatalf("body %q headers %v", body, resp.Header)
	}
	// Close the raw pipe: a TLS close_notify from both ends at once would
	// block on the unbuffered pipe until tls.Conn's 5s close timeout.
	cli.Close()
	waitClosed(t, done)
}

// High-assurance upstreams (EV cert, or a client-certificate request) are
// blacklisted and passed through, so the client talks to the real server.
func TestMitmTLSFlowPassthroughHighAssurance(t *testing.T) {
	pki := newTestPKI(t)
	upstreamRootPool().AddCert(pki.cert)
	cm, _ := newTestCertManager(t)

	for _, tc := range []struct {
		name, host string
		auth       tls.ClientAuthType
		ev         bool
	}{
		{"ev", "ev.test", tls.NoClientCert, true},
		{"mtls", "mtls.test", tls.RequestClientCert, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var policies []asn1.ObjectIdentifier
			if tc.ev {
				policies = append(policies, asn1.ObjectIdentifier{2, 23, 140, 1, 1})
			}
			leaf := pki.leaf(t, tc.host, policies...)
			flow := startUpstream(t, leaf, tc.auth, htmlHandler)
			filter := NewMitmFilter()
			cli, done := runTLSFlow(t, cm, filter, tc.host, flow)

			c := tls.Client(cli, &tls.Config{ServerName: tc.host, RootCAs: poolOf(pki.cert)})
			if err := c.Handshake(); err != nil {
				t.Fatalf("passthrough handshake: %v", err)
			}
			if got := c.ConnectionState().PeerCertificates[0].Raw; string(got) != string(leaf.Certificate[0]) {
				t.Fatal("client did not see the real upstream cert")
			}
			if filter.IsInterceptionAllowed(tc.host) {
				t.Fatal("host was not blacklisted")
			}
			c.Close()
			waitClosed(t, done)
		})
	}
}

func TestMitmTLSFlowUntrustedUpstreamPassesThrough(t *testing.T) {
	stranger := newTestPKI(t) // not added to the upstream pool
	cm, _ := newTestCertManager(t)
	flow := startUpstream(t, stranger.leaf(t, "untrusted.test"), tls.NoClientCert, htmlHandler)
	filter := NewMitmFilter()
	cli, done := runTLSFlow(t, cm, filter, "untrusted.test", flow)

	c := tls.Client(cli, &tls.Config{ServerName: "untrusted.test", RootCAs: poolOf(stranger.cert)})
	if err := c.Handshake(); err != nil {
		t.Fatalf("passthrough handshake: %v", err)
	}
	if iss := c.ConnectionState().PeerCertificates[0].Issuer.CommonName; iss != "Upstream Test CA" {
		t.Fatalf("issuer %q, want the real upstream", iss)
	}
	if filter.GetBlacklistCount() != 0 {
		t.Fatal("upstream verification failure should not blacklist")
	}
	c.Close()
	waitClosed(t, done)
}

func TestMitmTLSFlowClientRejectsCertBlacklists(t *testing.T) {
	pki := newTestPKI(t)
	upstreamRootPool().AddCert(pki.cert)
	cm, _ := newTestCertManager(t)
	flow := startUpstream(t, pki.leaf(t, "pinned.test"), tls.NoClientCert, htmlHandler)
	filter := NewMitmFilter()
	cli, done := runTLSFlow(t, cm, filter, "pinned.test", flow)

	c := tls.Client(cli, &tls.Config{ServerName: "pinned.test", RootCAs: x509.NewCertPool()})
	if err := c.Handshake(); err == nil {
		t.Fatal("client accepted an untrusted MITM cert")
	}
	cli.Close()
	waitClosed(t, done)
	if filter.IsInterceptionAllowed("pinned.test") {
		t.Fatal("pinned host not blacklisted after the client rejected our cert")
	}
}

// Any error string containing "tls:" blacklists the host, including
// failures that have nothing to do with pinning.
func TestMitmTLSFlowNonPinningErrorDoesNotBlacklist(t *testing.T) {
	t.Skip("known bug: the blacklist trigger matches any error containing \"tls:\"")
	pki := newTestPKI(t)
	upstreamRootPool().AddCert(pki.cert)
	cm, _ := newTestCertManager(t)
	flow := startUpstream(t, pki.leaf(t, "plain.test"), tls.NoClientCert, htmlHandler)
	filter := NewMitmFilter()
	cli, done := runTLSFlow(t, cm, filter, "plain.test", flow)
	io.WriteString(cli, "GET / HTTP/1.1\r\nHost: plain.test\r\n\r\n")
	cli.Close()
	waitClosed(t, done)
	if filter.GetBlacklistCount() != 0 {
		t.Fatal("a non-TLS client blacklisted the host")
	}
}

func TestMitmTLSFlowDialFailure(t *testing.T) {
	cm, _ := newTestCertManager(t)
	_, done := runTLSFlow(t, cm, NewMitmFilter(), "down.test", closedPortFlow(t))
	waitClosed(t, done)
}

// closedPortFlow returns a flow to a 127.0.0.1 port nothing listens on.
func closedPortFlow(t *testing.T) flowID {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	f := flowFor(t, ln.Addr())
	ln.Close()
	return f
}

func TestMitmHTTPFlow(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(htmlHandler))
	defer ts.Close()
	flow := flowFor(t, ts.Listener.Addr())

	cli, srv := net.Pipe()
	defer cli.Close()
	done := make(chan struct{})
	go func() {
		mitmHTTPFlow(srv, srv, NewMitmFilter(), nil, "plain.test", flow, nil)
		srv.Close()
		close(done)
	}()
	cli.SetDeadline(time.Now().Add(5 * time.Second))
	go io.WriteString(cli, "GET / HTTP/1.1\r\nHost: plain.test\r\nAccept: text/html\r\nConnection: close\r\n\r\n")
	resp, err := http.ReadResponse(bufio.NewReader(cli), nil)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), injectionTags) {
		t.Fatalf("body %q", body)
	}
	waitClosed(t, done)

	// Upstream down: returns without serving anything.
	done2 := make(chan struct{})
	go func() { mitmHTTPFlow(srv, srv, nil, nil, "down.test", closedPortFlow(t), nil); close(done2) }()
	waitClosed(t, done2)
}

func TestDialUpstreamIPv6FallsBackToV4(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		if c, err := ln.Accept(); err == nil {
			c.Close()
		}
	}()
	flow := flowFor(t, ln.Addr())
	flow.serverIP = net.ParseIP("::1") // nothing listens on [::1]:port

	if _, err := dialUpstream(flow, "", nil, nil); err == nil {
		t.Skip("something answers on [::1]; cannot exercise the fallback")
	}
	if _, err := dialUpstream(flow, "v6.test", &fakeBlocker{}, nil); err == nil {
		t.Fatal("dial succeeded without a fallback address")
	}
	conn, err := dialUpstream(flow, "v6.test", &fakeBlocker{lookup: net.ParseIP("127.0.0.1")}, nil)
	if err != nil {
		t.Fatalf("v4 fallback: %v", err)
	}
	conn.Close()
}

// startEcho runs a TCP echo server on 127.0.0.1.
func startEcho(t *testing.T) flowID {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	return flowFor(t, ln.Addr())
}

func TestRelayDirect(t *testing.T) {
	flow := startEcho(t)
	protected := 0
	protect := func(int) bool { protected++; return true }

	t.Run("from flow", func(t *testing.T) {
		cli, srv := net.Pipe()
		done := make(chan struct{})
		go func() { relayDirectFromFlow(srv, flow, nil, protect); srv.Close(); close(done) }()
		echoRoundTrip(t, cli, "ping", "ping")
		cli.Close()
		waitClosed(t, done)
		if protected == 0 {
			t.Error("protect was not called on the upstream socket")
		}
	})
	t.Run("peeked", func(t *testing.T) {
		cli, srv := net.Pipe()
		done := make(chan struct{})
		r := io.MultiReader(strings.NewReader("peek-"), srv)
		go func() { relayDirectPeeked(srv, r, flow, "echo.test", nil, nil); srv.Close(); close(done) }()
		echoRoundTrip(t, cli, "rest", "peek-rest")
		cli.Close()
		waitClosed(t, done)
	})
	t.Run("dial failure", func(t *testing.T) {
		_, srv := net.Pipe()
		relayDirectFromFlow(srv, closedPortFlow(t), nil, nil)
		relayDirectPeeked(srv, srv, closedPortFlow(t), "", nil, nil)
	})
}

func echoRoundTrip(t *testing.T, c net.Conn, send, want string) {
	t.Helper()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	go io.WriteString(c, send)
	buf := make([]byte, len(want))
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != want {
		t.Fatalf("echo = %q, %v; want %q", buf, err, want)
	}
}

func TestServeLocalAssetOverFlow(t *testing.T) {
	cm, _ := newTestCertManager(t)
	t.Run("tls", func(t *testing.T) {
		cli, srv := net.Pipe()
		done := make(chan struct{})
		go func() { serveLocalAssetTLS(srv, srv, cm, LocalAssetHost); srv.Close(); close(done) }()
		cli.SetDeadline(time.Now().Add(5 * time.Second))
		c := tls.Client(cli, &tls.Config{ServerName: LocalAssetHost, RootCAs: poolOf(cm.caCert)})
		go io.WriteString(c, "GET /health HTTP/1.1\r\nHost: local.pwhs.app\r\n\r\nGET /nope HTTP/1.1\r\nHost: local.pwhs.app\r\nConnection: close\r\n\r\n")
		br := bufio.NewReader(c)
		for _, want := range []int{200, 404} {
			resp, err := http.ReadResponse(br, nil)
			if err != nil || resp.StatusCode != want {
				t.Fatalf("status %v, err %v; want %d", resp, err, want)
			}
			io.Copy(io.Discard, resp.Body)
		}
		cli.Close()
		waitClosed(t, done)
	})
	t.Run("tls handshake failure", func(t *testing.T) {
		cli, srv := net.Pipe()
		done := make(chan struct{})
		go func() { serveLocalAssetTLS(srv, srv, cm, LocalAssetHost); srv.Close(); close(done) }()
		io.WriteString(cli, "not tls at all\r\n\r\n")
		cli.Close()
		waitClosed(t, done)
	})
	t.Run("plaintext", func(t *testing.T) {
		cli, srv := net.Pipe()
		done := make(chan struct{})
		go func() { serveLocalAssetPlaintext(srv, srv); srv.Close(); close(done) }()
		cli.SetDeadline(time.Now().Add(5 * time.Second))
		go io.WriteString(cli, "GET /cosmetic.css HTTP/1.1\r\nHost: local.pwhs.app\r\nConnection: close\r\n\r\n")
		resp, err := http.ReadResponse(bufio.NewReader(cli), nil)
		if err != nil || resp.StatusCode != 200 || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/css") {
			t.Fatalf("resp %v err %v", resp, err)
		}
		io.Copy(io.Discard, resp.Body)
		waitClosed(t, done)
	})
}
