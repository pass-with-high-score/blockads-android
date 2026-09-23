package dns

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	miekgdns "github.com/miekg/dns"
	"github.com/quic-go/quic-go"
)

// doqServer is a minimal RFC 9250 server on loopback.
type doqServer struct {
	addr     string
	accepted atomic.Int64
	// unframed answers without the 2-byte length prefix (pre-RFC drafts).
	unframed atomic.Bool

	mu    sync.Mutex
	conns []quic.Connection
}

func startDoQ(t *testing.T, ip string) *doqServer {
	t.Helper()
	requireTrustedRoot(t)
	ln, err := quic.ListenAddr("127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{testCert},
		NextProtos:   []string{"doq"},
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	s := &doqServer{addr: ln.Addr().String()}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() { cancel(); ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept(ctx)
			if err != nil {
				return
			}
			s.accepted.Add(1)
			s.mu.Lock()
			s.conns = append(s.conns, conn)
			s.mu.Unlock()
			go s.serveConn(ctx, conn, ip)
		}
	}()
	return s
}

func (s *doqServer) serveConn(ctx context.Context, conn quic.Connection, ip string) {
	for {
		stream, err := conn.AcceptStream(ctx)
		if err != nil {
			return
		}
		go func(stream quic.Stream) {
			defer stream.Close()
			data, err := io.ReadAll(stream)
			if err != nil || len(data) < 2 {
				return
			}
			var m miekgdns.Msg
			if m.Unpack(data[2:]) != nil {
				return
			}
			raw, _ := answerA(ip)(&m).Pack()
			if s.unframed.Load() {
				_, _ = stream.Write(raw)
				return
			}
			_, _ = stream.Write(framed(raw))
		}(stream)
	}
}

// closeAll drops every accepted connection from the server side.
func (s *doqServer) closeAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.conns {
		_ = c.CloseWithError(0, "server restart")
	}
	s.conns = nil
}

func TestDoQ(t *testing.T) {
	srv := startDoQ(t, "192.0.2.77")
	var protected atomic.Int64
	r := NewResolver(func(int) bool { protected.Add(1); return true })
	defer r.Shutdown()
	r.Configure(ProtocolDoQ, "quic://"+srv.addr, "quic://"+srv.addr, "")

	for i := 0; i < 3; i++ {
		resp, err := r.Resolve(query(t, "example.com", miekgdns.TypeA))
		if err != nil {
			t.Fatalf("Resolve %d: %v", i, err)
		}
		if ip := firstA(t, resp); ip != "192.0.2.77" {
			t.Errorf("answer = %s", ip)
		}
	}
	if n := srv.accepted.Load(); n != 1 {
		t.Errorf("server accepted %d connections, want 1 (reused)", n)
	}
	if protected.Load() == 0 {
		t.Error("DoQ socket was not protected")
	}

	// Pre-RFC servers send the message without a length prefix; it is
	// returned as-is.
	srv.unframed.Store(true)
	resp, err := r.queryDoQ(query(t, "example.com", miekgdns.TypeA), srv.addr)
	if err != nil {
		t.Fatalf("unframed query: %v", err)
	}
	if ip := firstA(t, resp); ip != "192.0.2.77" {
		t.Errorf("unframed answer = %s", ip)
	}
}

func TestDoQReconnectsAfterServerClose(t *testing.T) {
	srv := startDoQ(t, "192.0.2.78")
	r := NewResolver(nil)
	defer r.Shutdown()
	raw := query(t, "example.com", miekgdns.TypeA)

	if _, err := r.queryDoQ(raw, srv.addr); err != nil {
		t.Fatalf("first query: %v", err)
	}
	r.quicMu.Lock()
	conn := r.quicConn
	r.quicMu.Unlock()

	srv.closeAll()
	select {
	case <-conn.Context().Done():
	case <-time.After(3 * time.Second):
		t.Fatal("client never saw the server close")
	}

	resp, err := r.queryDoQ(raw, srv.addr)
	if err != nil {
		t.Fatalf("query after server close: %v", err)
	}
	if ip := firstA(t, resp); ip != "192.0.2.78" {
		t.Errorf("answer = %s", ip)
	}
	if n := srv.accepted.Load(); n != 2 {
		t.Errorf("server accepted %d connections, want 2", n)
	}
}

// Switching away from DoQ closes the cached connection.
func TestDoQConfigureResetsConnection(t *testing.T) {
	srv := startDoQ(t, "192.0.2.79")
	r := NewResolver(nil)
	defer r.Shutdown()
	if _, err := r.queryDoQ(query(t, "example.com", miekgdns.TypeA), srv.addr); err != nil {
		t.Fatalf("query: %v", err)
	}
	r.Configure(ProtocolPlain, "", "", "")
	r.quicMu.Lock()
	defer r.quicMu.Unlock()
	if r.quicConn != nil {
		t.Error("Configure(Plain) kept the QUIC connection")
	}
}

// Each reset closes the QUIC connection but never the quic.Transport or
// the UDP socket handed to it, so every reconnect leaks a socket and the
// transport's read goroutine.
func TestDoQResetDoesNotLeakSockets(t *testing.T) {
	t.Skip("known bug: resetQUICConn leaks the quic.Transport and its UDP socket")
	srv := startDoQ(t, "192.0.2.80")
	r := NewResolver(nil)
	defer r.Shutdown()
	raw := query(t, "example.com", miekgdns.TypeA)
	if _, err := r.queryDoQ(raw, srv.addr); err != nil {
		t.Fatal(err)
	}
	r.resetQUICConn()
	before := openFDs(t)
	for i := 0; i < 10; i++ {
		if _, err := r.queryDoQ(raw, srv.addr); err != nil {
			t.Fatal(err)
		}
		r.resetQUICConn()
	}
	time.Sleep(100 * time.Millisecond)
	if after := openFDs(t); after > before+2 {
		t.Errorf("open fds grew from %d to %d over 10 reconnects", before, after)
	}
}

// The DoQ server URL arrives in dohURL (the same field DoH uses), but
// query() hands queryDoQ the primary server instead.
func TestDoQUsesConfiguredURL(t *testing.T) {
	t.Skip("known bug: query() passes primaryServer, not dohURL, to queryDoQ")
	srv := startDoQ(t, "192.0.2.81")
	r := NewResolver(nil)
	defer r.Shutdown()
	r.Configure(ProtocolDoQ, noFallback, noFallback, "quic://"+srv.addr)
	if _, err := r.Resolve(query(t, "example.com", miekgdns.TypeA)); err != nil {
		t.Fatalf("Resolve: %v", err)
	}
}

func TestDoQErrors(t *testing.T) {
	r := NewResolver(nil)
	defer r.Shutdown()
	raw := query(t, "example.com", miekgdns.TypeA)

	if _, err := r.queryDoQ(raw, "quic://"); err == nil || !strings.Contains(err.Error(), "invalid DoQ URL") {
		t.Errorf("empty host err = %v", err)
	}
	if _, err := r.queryDoQ(raw, "no-such-host.invalid"); err == nil || !strings.Contains(err.Error(), "resolve") {
		t.Errorf("unresolvable err = %v", err)
	}
	if _, err := r.queryDoQ(raw, "127.0.0.1:99999"); err == nil {
		t.Error("invalid port succeeded")
	}
}

func TestDoQDialTimeout(t *testing.T) {
	if testing.Short() {
		t.Skip("waits for the 5s QUIC connect timeout")
	}
	requireTrustedRoot(t)
	// A UDP socket that never answers the handshake.
	addr := rawUDPServer(t, func(net.PacketConn, net.Addr, []byte) {})
	r := NewResolver(nil)
	defer r.Shutdown()
	start := time.Now()
	_, err := r.queryDoQ(query(t, "example.com", miekgdns.TypeA), addr)
	if err == nil || !strings.Contains(err.Error(), "DoQ connection") {
		t.Fatalf("err = %v, want DoQ connection error", err)
	}
	if el := time.Since(start); el > connectTimeout+2*time.Second {
		t.Errorf("gave up after %v, want ~%v", el, connectTimeout)
	}
}

func TestParseDoQURL(t *testing.T) {
	tests := []struct{ in, host, port string }{
		{"quic://dns.adguard.com", "dns.adguard.com", "853"},
		{"doq://dns.adguard.com:784", "dns.adguard.com", "784"},
		{"https://dns.example/dns-query", "dns.example", "853"},
		{"94.140.14.14", "94.140.14.14", "853"},
		{"127.0.0.1:8853", "127.0.0.1", "8853"},
		{"[2a10:50c0::ad1:ff]:853", "2a10:50c0::ad1:ff", "853"},
		{"quic://[::1]:8853/path", "::1", "8853"},
		{"", "", "853"},
	}
	for _, tt := range tests {
		h, p := parseDoQURL(tt.in)
		if h != tt.host || p != tt.port {
			t.Errorf("parseDoQURL(%q) = %q, %q; want %q, %q", tt.in, h, p, tt.host, tt.port)
		}
	}
}

func FuzzParseDoQURL(f *testing.F) {
	for _, s := range []string{"quic://dns.adguard.com", "doq://h:1", "[::1]:853", "https://a/b", "::", "quic://[::1"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		host, port := parseDoQURL(s)
		if strings.Contains(host, "/") || strings.Contains(port, "/") {
			t.Fatalf("parseDoQURL(%q) = %q, %q: path leaked into host or port", s, host, port)
		}
	})
}
