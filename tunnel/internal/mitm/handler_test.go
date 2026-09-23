package mitm

import (
	"io"
	"net"
	"net/http/httptest"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// NewMitmTcpHandler's Gate 0 passes loopback and private destinations
// straight through, so the MITM gates past it (peek, SNI block, UID,
// interception) cannot be reached against a local server without a dial
// seam. These tests cover the gates in front of Gate 0 and the Gate 0
// passthrough itself; the MITM paths are covered by calling mitmTLSFlow,
// mitmHTTPFlow and relayHTTPFlow directly.

func endpointID(src, dst net.IP, srcPort, dstPort uint16) *stack.TransportEndpointID {
	addr := func(ip net.IP) tcpip.Address {
		if v4 := ip.To4(); v4 != nil {
			return tcpip.AddrFrom4Slice(v4)
		}
		return tcpip.AddrFrom16Slice(ip.To16())
	}
	return &stack.TransportEndpointID{
		LocalAddress: addr(dst), LocalPort: dstPort,
		RemoteAddress: addr(src), RemotePort: srcPort,
	}
}

type fakeTCPConn struct {
	net.Conn
	id *stack.TransportEndpointID
}

func (c *fakeTCPConn) ID() *stack.TransportEndpointID { return c.id }

type fakeUDPConn struct {
	net.Conn
	id     *stack.TransportEndpointID
	closed atomic.Bool
}

func (c *fakeUDPConn) ID() *stack.TransportEndpointID            { return c.id }
func (c *fakeUDPConn) ReadFrom([]byte) (int, net.Addr, error)    { return 0, nil, io.EOF }
func (c *fakeUDPConn) WriteTo(b []byte, _ net.Addr) (int, error) { return len(b), nil }
func (c *fakeUDPConn) Close() error                              { c.closed.Store(true); return nil }

type fixedUID int

func (u fixedUID) ResolveUID(int, string, int, string, int) int { return int(u) }

var clientIP = net.ParseIP("10.0.0.2")

// runHandler invokes the handler for a flow to dst:port and reports whether
// it returned (closing the client side) within the timeout.
func runHandler(t *testing.T, h TcpFlowHandler, dst net.IP, port uint16) (net.Conn, chan struct{}) {
	t.Helper()
	cli, srv := net.Pipe()
	done := make(chan struct{})
	go func() {
		h(&fakeTCPConn{Conn: srv, id: endpointID(clientIP, dst, 40000, port)})
		close(done)
	}()
	t.Cleanup(func() { cli.Close() })
	return cli, done
}

func TestMitmTcpHandlerEarlyGates(t *testing.T) {
	cm, _ := newTestCertManager(t)
	for _, tc := range []struct {
		name    string
		dst     string
		port    uint16
		doh     bool
		wantLog int
	}{
		{"unspecified", "0.0.0.0", 443, false, 0},
		{"dot port", "93.184.216.34", 853, false, 1},
		{"virtual dns v4", "100.64.100.1", 443, false, 1},
		{"virtual dns v6", "fd00::1", 80, false, 1},
		{"doh ip", "8.8.8.8", 443, true, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			blocker := &fakeBlocker{doh: tc.doh}
			h := NewMitmTcpHandler(cm, NewMitmFilter(), blocker, nil, nil)
			_, done := runHandler(t, h, net.ParseIP(tc.dst), tc.port)
			waitClosed(t, done)
			if blocker.logs() != tc.wantLog {
				t.Errorf("LogConnection called %d times, want %d", blocker.logs(), tc.wantLog)
			}
		})
	}
}

func TestMitmTcpHandlerGate0Passthrough(t *testing.T) {
	cm, _ := newTestCertManager(t)
	flow := startEcho(t)
	h := NewMitmTcpHandler(cm, NewMitmFilter(), nil, fixedUID(10100), func(int) bool { return true })
	cli, done := runHandler(t, h, flow.serverIP, flow.serverPort)
	echoRoundTrip(t, cli, "loopback", "loopback")
	cli.Close()
	waitClosed(t, done)
}

func TestMitmUdpHandler(t *testing.T) {
	filterWith := func(uids ...int) *MitmFilter {
		f := NewMitmFilter()
		if len(uids) > 0 {
			f.SetAllowedUIDs(uids)
		}
		return f
	}
	for _, tc := range []struct {
		name      string
		filter    *MitmFilter
		uidr      UIDResolver
		port      uint16
		wantRelay bool
	}{
		{"quic, no filter", nil, nil, 443, false},
		{"quic, no browsers configured", filterWith(), nil, 443, false},
		{"quic, browser uid", filterWith(10100), fixedUID(10100), 443, false},
		{"quic, unknown uid", filterWith(10100), nil, 443, false},
		{"quic, other app", filterWith(10100), fixedUID(10200), 443, true},
		{"dns", filterWith(10100), fixedUID(10100), 53, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			relayed := false
			h := NewMitmUdpHandler(tc.filter, tc.uidr, func(adapter.UDPConn) { relayed = true })
			conn := &fakeUDPConn{id: endpointID(clientIP, net.ParseIP("93.184.216.34"), 5000, tc.port)}
			h(conn)
			if relayed != tc.wantRelay || conn.closed.Load() == tc.wantRelay {
				t.Errorf("relayed=%v closed=%v, want relayed=%v", relayed, conn.closed.Load(), tc.wantRelay)
			}
		})
	}
	// A nil base relay must not panic.
	NewMitmUdpHandler(nil, nil, nil)(&fakeUDPConn{id: endpointID(clientIP, clientIP, 1, 53)})
}

func TestFlowIDAccessorsAndUID(t *testing.T) {
	c := &fakeTCPConn{id: endpointID(clientIP, net.ParseIP("2001:db8::1"), 1234, 443)}
	f := tcpFlowID(c)
	if !f.ClientIP().Equal(clientIP) || f.ClientPort() != 1234 || !f.ServerIP().Equal(net.ParseIP("2001:db8::1")) || f.ServerPort() != 443 {
		t.Fatalf("flow = %+v", f)
	}
	u := udpFlowID(&fakeUDPConn{id: endpointID(clientIP, clientIP, 1, 2)})
	if u.ServerPort() != 2 {
		t.Fatalf("udp flow = %+v", u)
	}
	if resolveFlowUID(nil, ProtocolTCP, f) != UIDUnknown || resolveFlowUID(fixedUID(7), ProtocolTCP, f) != 7 {
		t.Fatal("resolveFlowUID mismatch")
	}
}

func TestIsLoopbackOrInternal(t *testing.T) {
	for host, want := range map[string]bool{
		"localhost": true, "LOCALHOST": true, "0.0.0.0": true, "::": true,
		"127.0.0.1": true, "::1": true, "10.1.2.3": true, "172.16.0.1": true,
		"172.31.255.255": true, "192.168.1.1": true, "169.254.1.1": true, "fe80::1": true,
		"172.32.0.1": false, "8.8.8.8": false, "example.com": false, "2001:db8::1": false,
	} {
		if got := isLoopbackOrInternal(host); got != want {
			t.Errorf("isLoopbackOrInternal(%q) = %v, want %v", host, got, want)
		}
	}
}

func TestIsKnownPublicDoHIP(t *testing.T) {
	for ip, want := range map[string]bool{
		"1.1.1.1": true, "8.8.4.4": true, "2606:4700:4700::1111": true, "2620:fe::9": true,
		"1.1.1.2": false, "93.184.216.34": false,
	} {
		if got := IsKnownPublicDoHIP(net.ParseIP(ip)); got != want {
			t.Errorf("IsKnownPublicDoHIP(%s) = %v, want %v", ip, got, want)
		}
	}
}

func TestRequestAcceptsHTML(t *testing.T) {
	for _, tc := range []struct {
		target, accept, upgrade string
		want                    bool
	}{
		{"/app.js", "text/html,*/*", "", true},
		{"/app.js", "*/*", "1", true},
		{"/", "*/*", "", true},
		{"/page.HTML", "*/*", "", true},
		{"/doc.htm", "*/*", "", true},
		{"/articles/some-slug", "*/*", "", true},
		{"/app.js", "*/*", "", false},
		{"/img.png", "image/*", "", false},
	} {
		r := httptest.NewRequest("GET", tc.target, nil)
		r.Header.Set("Accept", tc.accept)
		if tc.upgrade != "" {
			r.Header.Set("Upgrade-Insecure-Requests", tc.upgrade)
		}
		if got := requestAcceptsHTML(r); got != tc.want {
			t.Errorf("requestAcceptsHTML(%s, %q) = %v, want %v", tc.target, tc.accept, got, tc.want)
		}
	}
}

func TestProtectedControl(t *testing.T) {
	if protectedControl(nil) != nil {
		t.Fatal("nil protect should yield a nil Control")
	}
	var fds []int
	ctl := protectedControl(func(fd int) bool { fds = append(fds, fd); return true })
	d := net.Dialer{Control: ctl, Timeout: time.Second}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	c, err := d.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	if len(fds) != 1 || fds[0] <= 0 {
		t.Fatalf("protect saw fds %v", fds)
	}
	var _ func(string, string, syscall.RawConn) error = ctl
}

func TestBidiCopyFlow(t *testing.T) {
	flow := startEcho(t)
	remote, err := net.Dial("tcp", net.JoinHostPort(flow.serverIP.String(), intToStr(int(flow.serverPort))))
	if err != nil {
		t.Fatal(err)
	}
	cli, srv := net.Pipe()
	done := make(chan struct{})
	go func() { bidiCopyFlow(srv, remote); remote.Close(); close(done) }()
	echoRoundTrip(t, cli, "bidi", "bidi")
	cli.Close()
	waitClosed(t, done)
}
