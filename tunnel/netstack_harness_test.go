//go:build linux

package tunnel

import (
	"context"
	"net"
	"net/netip"
	"runtime"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/nqmgaming/blockads-tunnel/internal/testnet"
	"golang.org/x/sys/unix"
)

// fakeDNSIP is the VPN's DNS server address as the app configures it. The
// engine answers DNS for any destination, so its value only has to be
// routable from the client stack.
var fakeDNSIP = netip.MustParseAddr("10.0.0.1")

// netHarness runs an Engine over a socketpair TUN with a gVisor client stack
// on the other end.
type netHarness struct {
	e    *Engine
	c    *testnet.Client
	fd   int
	done chan struct{}
}

// runEngine starts e.Start (full=false) or e.StartFull (full=true) on a fresh
// socketpair and waits until the data path is up. Cleanup stops the engine.
func runEngine(t *testing.T, e *Engine, full bool) *netHarness {
	t.Helper()
	return runEngineProtected(t, e, full, nil)
}

// runEngineProtected is runEngine with a SocketProtector.
func runEngineProtected(t *testing.T, e *Engine, full bool, p SocketProtector) *netHarness {
	t.Helper()
	fd, cf, err := testnet.Socketpair()
	if err != nil {
		t.Fatal(err)
	}
	c, err := testnet.NewClient(cf)
	if err != nil {
		unix.Close(fd)
		t.Fatal(err)
	}
	h := &netHarness{e: e, c: c, fd: fd, done: make(chan struct{})}
	go func() {
		defer close(h.done)
		if full {
			e.StartFull(fd, p)
		} else {
			e.Start(fd, p, "")
		}
	}()
	waitUntil(t, "engine data path up", func() bool {
		if full {
			e.mu.Lock()
			st := e.tcpStack
			e.mu.Unlock()
			return st != nil && st.IsRunning()
		}
		return e.interceptor.IsRunning()
	})
	t.Cleanup(h.stop)
	return h
}

// stop stops the engine, waits for Start/StartFull to return and releases
// the socketpair. Safe to call more than once.
func (h *netHarness) stop() {
	h.e.Stop()
	select {
	case <-h.done:
	case <-time.After(5 * time.Second):
		panic("engine Start did not return within 5s of Stop")
	}
	h.c.Close()
	if h.fd >= 0 {
		unix.Close(h.fd)
		h.fd = -1
	}
}

// query sends one DNS question over UDP from the client to dst and returns the
// reply, or nil if none arrives within wait.
func (h *netHarness) query(t *testing.T, dst netip.Addr, name string, qtype uint16, wait time.Duration) *dns.Msg {
	t.Helper()
	conn, err := h.c.DialUDP(netip.AddrPortFrom(dst, 53))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	m := new(dns.Msg)
	m.SetQuestion(dns.Fqdn(name), qtype)
	out, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write(out); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(wait))
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil
	}
	var r dns.Msg
	if err := r.Unpack(buf[:n]); err != nil {
		t.Fatalf("reply is not DNS: %v", err)
	}
	if r.Id != m.Id {
		t.Fatalf("reply id %d, want %d", r.Id, m.Id)
	}
	return &r
}

func (h *netHarness) mustQuery(t *testing.T, name string, qtype uint16) *dns.Msg {
	t.Helper()
	r := h.query(t, fakeDNSIP, name, qtype, 3*time.Second)
	if r == nil {
		t.Fatalf("no DNS reply for %s", name)
	}
	return r
}

// echoTCP dials dst through the client stack, sends msg and returns what
// comes back.
func (h *netHarness) echoTCP(t *testing.T, dst netip.AddrPort, msg string) (string, error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := h.c.DialTCP(ctx, dst)
	if err != nil {
		return "", err
	}
	defer conn.Close()
	return roundTrip(conn, msg)
}

// echoUDP sends msg to dst through the client stack and returns the reply.
func (h *netHarness) echoUDP(t *testing.T, dst netip.AddrPort, msg string) (string, error) {
	t.Helper()
	conn, err := h.c.DialUDP(dst)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	return roundTrip(conn, msg)
}

// sendUDP sends one datagram to dst through the client stack without
// waiting for a reply.
func (h *netHarness) sendUDP(t *testing.T, dst netip.AddrPort, msg string) {
	t.Helper()
	conn, err := h.c.DialUDP(dst)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte(msg)); err != nil {
		t.Fatal(err)
	}
}

func roundTrip(conn net.Conn, msg string) (string, error) {
	_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write([]byte(msg)); err != nil {
		return "", err
	}
	buf := make([]byte, len(msg))
	n := 0
	for n < len(buf) {
		m, err := conn.Read(buf[n:])
		n += m
		if err != nil {
			return string(buf[:n]), err
		}
	}
	return string(buf), nil
}

// waitUntil polls cond for up to 5s.
func waitUntil(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// newNetEngine returns an engine resolving through a loopback upstream that
// answers every A query with 192.0.2.1 and blocks ads.example.com through a
// fake DomainChecker (a custom block rule, which both DNS paths honor).
func newNetEngine(t *testing.T) (*Engine, *upstream) {
	t.Helper()
	up := startUpstream(t, answerWith("A 192.0.2.1"))
	e := NewEngine()
	e.SetDNS("PLAIN", up.addr, up.addr, "")
	e.SetDomainChecker(&fakeChecker{
		custom:  map[string]int{"ads.example.com": 1},
		blocked: map[string]bool{"ads.example.com": true},
	})
	return e, up
}

// leakCheck fails the test if goroutines started after this call outlive
// the test (run it before the harness so its cleanup runs last).
func leakCheck(t *testing.T) {
	t.Helper()
	baseline := runtime.NumGoroutine()
	t.Cleanup(func() {
		// Goroutines wind down asynchronously after Stop, so poll before failing.
		deadline := time.Now().Add(5 * time.Second)
		for runtime.NumGoroutine() > baseline && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
		}
		if n := runtime.NumGoroutine(); n > baseline {
			buf := make([]byte, 1<<20)
			t.Errorf("%d goroutines leaked:\n%s", n-baseline, buf[:runtime.Stack(buf, true)])
		}
	})
}

// closedPort returns a TCP port on ip that nothing listens on.
func closedPort(t *testing.T, ip netip.Addr) uint16 {
	t.Helper()
	ln, err := net.Listen("tcp", netip.AddrPortFrom(ip, 0).String())
	if err != nil {
		t.Fatal(err)
	}
	port := netip.MustParseAddrPort(ln.Addr().String()).Port()
	ln.Close()
	return port
}

// countingProtector records every socket the engine asks to protect.
type countingProtector struct{ calls atomic.Int64 }

func (p *countingProtector) Protect(fd int) bool {
	if fd < 0 {
		panic("Protect called with a negative fd")
	}
	p.calls.Add(1)
	return true
}
