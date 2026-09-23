//go:build linux

package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/nqmgaming/blockads-tunnel/internal/testnet"
	"golang.org/x/sys/unix"
	gstack "gvisor.dev/gvisor/pkg/tcpip/stack"
)

// stackPair starts a bare TcpIpStack on one end of a socketpair and a client
// stack on the other.
func stackPair(t *testing.T, s *TcpIpStack) *testnet.Client {
	t.Helper()
	fd, cf, err := testnet.Socketpair()
	if err != nil {
		t.Fatal(err)
	}
	if err := unix.SetNonblock(fd, true); err != nil {
		t.Fatal(err)
	}
	tun := os.NewFile(uintptr(fd), "tun")
	c, err := testnet.NewClient(cf)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Start(tun, defaultTunMTU); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		tun.Close()
		s.Stop()
		c.Close()
	})
	return c
}

func TestTcpIpStackStartStop(t *testing.T) {
	leakCheck(t)
	s := NewTcpIpStack()
	s.Stop() // not running: no-op
	if err := s.Start(nil, defaultTunMTU); err == nil {
		t.Error("Start with a nil ReadWriter succeeded")
	}
	if s.IsRunning() {
		t.Fatal("running after a failed Start")
	}
	_ = stackPair(t, s)
	if !s.IsRunning() {
		t.Fatal("IsRunning = false after Start")
	}
	if err := s.Start(nil, defaultTunMTU); err == nil {
		t.Error("second Start succeeded")
	}
	s.Stop()
	s.Stop()
	if s.IsRunning() {
		t.Error("IsRunning after Stop")
	}
}

// With no handlers registered every flow is closed, but still counted and
// attributed through the UID resolver.
func TestTcpIpStackWithoutHandlers(t *testing.T) {
	s := NewTcpIpStack()
	uidr := &countingUIDResolver{uid: 10100}
	s.SetUIDResolver(uidr)
	c := stackPair(t, s)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := c.DialTCP(ctx, netip.MustParseAddrPort("192.0.2.10:80"))
	if err == nil {
		_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		if _, err := conn.Read(make([]byte, 1)); err == nil || errors.Is(err, os.ErrDeadlineExceeded) {
			t.Errorf("TCP flow without a handler was not closed: %v", err)
		}
		conn.Close()
	}
	waitUntil(t, "TCP flow counted", func() bool { return s.TcpFlowCount() == 1 })

	u, err := c.DialUDP(netip.MustParseAddrPort("192.0.2.10:9"))
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	if _, err := u.Write([]byte("x")); err != nil {
		t.Fatal(err)
	}
	waitUntil(t, "UDP flow counted", func() bool { return s.UdpFlowCount() == 1 })
	if uidr.calls.Load() < 2 {
		t.Errorf("UID resolver called %d times, want once per flow", uidr.calls.Load())
	}
}

// The protected TCP handler gives up on flows it can't or mustn't dial.
func TestProtectedTcpHandlerGates(t *testing.T) {
	ip := testnet.HostIPv4(t)
	s := NewTcpIpStack()
	p := &countingProtector{}
	s.SetTcpHandler(newProtectedTcpHandler(nil, p.Protect))
	s.SetUdpHandler(newProtectedUdpHandler(nil, p.Protect))
	c := stackPair(t, s)

	echo := testnet.TCPEcho(t, ip)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := c.DialTCP(ctx, echo)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := roundTrip(conn, "protected"); err != nil || got != "protected" {
		t.Errorf("echo = %q, %v", got, err)
	}
	conn.Close()
	if p.calls.Load() != 1 {
		t.Errorf("Protect called %d times, want 1", p.calls.Load())
	}

	for _, dst := range []netip.AddrPort{
		netip.AddrPortFrom(ip, 853),
		netip.AddrPortFrom(ip, closedPort(t, ip)),
	} {
		conn, err := c.DialTCP(ctx, dst)
		if err != nil {
			continue
		}
		_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		if _, err := conn.Read(make([]byte, 1)); err != io.EOF {
			t.Errorf("%s: read = %v, want EOF", dst, err)
		}
		conn.Close()
	}
}

func TestProtectedControl(t *testing.T) {
	if protectedControl(nil) != nil {
		t.Error("protectedControl(nil) should leave the dialer's Control unset")
	}
	var got int
	ctl := protectedControl(func(fd int) bool { got = fd; return true })
	d := net.Dialer{Control: ctl}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	conn, err := d.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if got <= 0 {
		t.Errorf("protect saw fd %d", got)
	}
}

// ── bufferedTun ──────────────────────────────────────────────────────────────

// Writes never block: once the drain stalls on a full pipe, the queue fills
// and further packets are dropped. A write error stops the drain.
func TestBufferedTunDropsOnOverflow(t *testing.T) {
	leakCheck(t)
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	b := newBufferedTun(w)
	pkt := make([]byte, 1500)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for i := 0; i < tunWriteQueueDepth+200; i++ {
			if n, err := b.Write(pkt); n != len(pkt) || err != nil {
				t.Errorf("Write = %d, %v", n, err)
				return
			}
		}
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("bufferedTun.Write blocked on a stalled TUN")
	}
	// The drain's blocked write now fails with EPIPE and it exits on its
	// own; leakCheck confirms it is gone.
	r.Close()
	b.halt()
	w.Close()
}

func TestBufferedTunReadAndHalt(t *testing.T) {
	leakCheck(t)
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	a := os.NewFile(uintptr(fds[0]), "a")
	peer := os.NewFile(uintptr(fds[1]), "b")
	defer a.Close()
	defer peer.Close()

	b := newBufferedTun(a)
	if _, err := peer.Write([]byte("in")); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 16)
	if n, err := b.Read(buf); err != nil || string(buf[:n]) != "in" {
		t.Errorf("Read = %q, %v", buf[:n], err)
	}
	if _, err := b.Write([]byte("out")); err != nil {
		t.Fatal(err)
	}
	if n, err := peer.Read(buf); err != nil || string(buf[:n]) != "out" {
		t.Errorf("drained packet = %q, %v", buf[:n], err)
	}
	b.halt()
}

// ── udpDNSResponseWriter ─────────────────────────────────────────────────────

// fakeUDPConn turns a connected net.Conn into an adapter.UDPConn.
type fakeUDPConn struct{ net.Conn }

func (fakeUDPConn) ReadFrom([]byte) (int, net.Addr, error) { return 0, nil, io.EOF }
func (fakeUDPConn) WriteTo([]byte, net.Addr) (int, error)  { return 0, io.EOF }
func (fakeUDPConn) ID() *gstack.TransportEndpointID        { return &gstack.TransportEndpointID{} }

func TestUDPDNSResponseWriter(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	conn, err := net.Dial("udp", pc.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	w := &udpDNSResponseWriter{conn: fakeUDPConn{conn}}

	if w.LocalAddr().String() != conn.LocalAddr().String() || w.RemoteAddr().String() != pc.LocalAddr().String() {
		t.Error("addresses not taken from the flow")
	}
	if n, err := w.Write([]byte("raw")); n != 3 || err != nil {
		t.Errorf("Write = %d, %v", n, err)
	}
	if err := w.WriteMsg(msgFor("example.com", dns.TypeA)); err != nil {
		t.Errorf("WriteMsg: %v", err)
	}
	bad := new(dns.Msg)
	bad.Question = []dns.Question{{Name: "not-fqdn", Qtype: dns.TypeA}}
	if err := w.WriteMsg(bad); err == nil {
		t.Error("WriteMsg packed an invalid message")
	}
	if w.Close() != nil || w.TsigStatus() != nil {
		t.Error("stub methods returned errors")
	}
	w.TsigTimersOnly(true)
	w.Hijack()

	buf := make([]byte, 512)
	_ = pc.SetReadDeadline(time.Now().Add(2 * time.Second))
	for _, want := range []string{"raw", "msg"} {
		n, _, err := pc.ReadFrom(buf)
		if err != nil {
			t.Fatalf("read %s: %v", want, err)
		}
		if want == "raw" && string(buf[:n]) != "raw" {
			t.Errorf("raw write = %q", buf[:n])
		}
	}
}
