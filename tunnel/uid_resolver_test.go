package tunnel

import (
	"net"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// fakeStackConn satisfies adapter.TCPConn and adapter.UDPConn for flow-ID
// extraction; only ID is ever called.
type fakeStackConn struct {
	net.PacketConn
	net.Conn
	id stack.TransportEndpointID
}

func (c *fakeStackConn) ID() *stack.TransportEndpointID { return &c.id }

// Resolve ambiguous selectors between the embedded interfaces.
func (c *fakeStackConn) Close() error                     { return nil }
func (c *fakeStackConn) LocalAddr() net.Addr              { return nil }
func (c *fakeStackConn) SetDeadline(time.Time) error      { return nil }
func (c *fakeStackConn) SetReadDeadline(time.Time) error  { return nil }
func (c *fakeStackConn) SetWriteDeadline(time.Time) error { return nil }

// recordingUIDResolver keeps the arguments of the last lookup.
type recordingUIDResolver struct {
	mu                 sync.Mutex
	proto              int
	localIP, remoteIP  string
	localPort, remPort int
	uid                int
}

func (r *recordingUIDResolver) ResolveUID(protocol int, localIP string, localPort int, remoteIP string, remotePort int) int {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.proto, r.localIP, r.localPort, r.remoteIP, r.remPort = protocol, localIP, localPort, remoteIP, remotePort
	return r.uid
}

// tun2socks names endpoints from the stack's side: Local is the server the
// app dialed, Remote is the app's socket. The Android API wants the app's
// socket as "local", so the flow ID must swap them, for both families.
func TestFlowIDDirection(t *testing.T) {
	tests := []struct {
		name          string
		appIP, server string
	}{
		{"ipv4", "10.0.0.2", "93.184.216.34"},
		{"ipv6", "fd00::2", "2606:2800:220:1:248:1893:25c8:1946"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			app, srv := net.ParseIP(tt.appIP), net.ParseIP(tt.server)
			if a4 := app.To4(); a4 != nil {
				app, srv = a4, srv.To4()
			}
			conn := &fakeStackConn{id: stack.TransportEndpointID{
				LocalAddress:  tcpip.AddrFromSlice(srv),
				LocalPort:     443,
				RemoteAddress: tcpip.AddrFromSlice(app),
				RemotePort:    50123,
			}}

			for _, tc := range []struct {
				proto int
				flow  flowID
			}{
				{ProtocolTCP, tcpFlowID(conn)},
				{ProtocolUDP, udpFlowID(conn)},
			} {
				f := tc.flow
				if !f.appIP.Equal(app) || f.appPort != 50123 || !f.serverIP.Equal(srv) || f.serverPort != 443 {
					t.Fatalf("flow = %+v, want app %s:50123 server %s:443", f, app, srv)
				}
				r := &recordingUIDResolver{uid: 10077}
				if uid := resolveFlowUID(r, tc.proto, f); uid != 10077 {
					t.Errorf("uid = %d", uid)
				}
				if r.proto != tc.proto || r.localIP != tt.appIP || r.localPort != 50123 || r.remoteIP != tt.server || r.remPort != 443 {
					t.Errorf("resolver got proto=%d local=%s:%d remote=%s:%d; want app as local, server as remote",
						r.proto, r.localIP, r.localPort, r.remoteIP, r.remPort)
				}
			}
		})
	}
	if uid := resolveFlowUID(nil, ProtocolTCP, testFlow(1)); uid != UIDUnknown {
		t.Errorf("nil resolver uid = %d, want UIDUnknown", uid)
	}
}

func TestAppNameForFlow(t *testing.T) {
	resetConnLogState()
	e := NewEngine()
	if got := e.appNameForFlow(testFlow(1), ProtocolTCP); got != "" {
		t.Errorf("no resolvers: %q", got)
	}
	e.SetUIDResolver(&countingUIDResolver{uid: UIDUnknown})
	e.SetAppUidResolver(&countingPackageResolver{pkg: "com.example"})
	if got := e.appNameForFlow(testFlow(1), ProtocolTCP); got != "" {
		t.Errorf("unknown uid: %q", got)
	}
	e.SetUIDResolver(&countingUIDResolver{uid: 10200})
	if got := e.appNameForFlow(testFlow(1), ProtocolUDP); got != "com.example" {
		t.Errorf("appNameForFlow = %q, want com.example", got)
	}
}

func TestReportConnectionLabels(t *testing.T) {
	resetConnLogState()
	tests := []struct {
		name    string
		uidr    UIDResolver
		pkgr    AppUidResolver
		proto   int
		wantApp string
		wantDom string
	}{
		{"no uid resolver", nil, nil, ProtocolTCP, "unknown", "TCP 93.184.216.34:443"},
		{"uid unknown", &countingUIDResolver{uid: UIDUnknown}, &countingPackageResolver{pkg: "x"}, ProtocolUDP, "unknown", "UDP 93.184.216.34:443"},
		{"uid without package", &countingUIDResolver{uid: 10300}, &countingPackageResolver{}, ProtocolTCP, "uid:10300", "TCP 93.184.216.34:443"},
		{"uid without package resolver", &countingUIDResolver{uid: 10301}, nil, ProtocolTCP, "uid:10301", "TCP 93.184.216.34:443"},
		{"package", &countingUIDResolver{uid: 10302}, &countingPackageResolver{pkg: "com.chat"}, ProtocolUDP, "com.chat", "UDP 93.184.216.34:443"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			e := NewEngine()
			if tt.uidr != nil {
				e.SetUIDResolver(tt.uidr)
			}
			if tt.pkgr != nil {
				e.SetAppUidResolver(tt.pkgr)
			}
			log := &entryLog{}
			e.reportConnection(log, testFlow(1), tt.proto)
			got := log.all()
			if len(got) != 1 {
				t.Fatalf("entries = %+v", got)
			}
			l := got[0]
			if l.app != tt.wantApp || l.domain != tt.wantDom || l.blockedBy != "connection" || l.resolvedIP != "93.184.216.34" || l.blocked {
				t.Errorf("entry = %+v, want app=%s domain=%q", l, tt.wantApp, tt.wantDom)
			}
		})
	}

	// Without a callback nothing is spawned.
	e := NewEngine()
	e.SetConnLogEnabled(true)
	if !e.IsConnLogEnabled() {
		t.Error("IsConnLogEnabled = false")
	}
	e.logConnection(testFlow(2), ProtocolTCP)
}

// blockingPackageResolver stalls lookups for one UID until released.
type blockingPackageResolver struct {
	slowUID int
	release chan struct{}
}

func (b *blockingPackageResolver) PackageForUid(uid int) string {
	if uid == b.slowUID {
		<-b.release
	}
	return "pkg." + string(rune('a'+uid%26))
}

// A binder call that hangs for one UID must not stall lookups for others.
// packageForUidCached serializes every lookup behind one global mutex.
func TestSlowPackageLookupDoesNotBlockOtherUIDs(t *testing.T) {
	t.Skip("known bug: packageForUidCached holds one global mutex across the binder call, so one slow UID blocks all others")
	resetConnLogState()
	r := &blockingPackageResolver{slowUID: 10001, release: make(chan struct{})}
	defer close(r.release)
	go packageForUidCached(r, 10001)
	time.Sleep(20 * time.Millisecond)

	done := make(chan string, 1)
	go func() { done <- packageForUidCached(r, 10002) }()
	select {
	case <-done:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("lookup for an unrelated UID blocked behind the slow one")
	}
}

// conn_log.go documents both caches as "cleared on engine stop"; only
// StartFull clears them, so a Start (non-full) session with stack MITM keeps
// the previous session's dedup set and uid→package map.
func TestConnLogCachesClearedOnStop(t *testing.T) {
	t.Skip("known bug: Stop never clears connLogSeen or uidPackageCache")
	resetConnLogState()
	e := NewEngine()
	e.SetUIDResolver(&countingUIDResolver{uid: 10400})
	e.SetAppUidResolver(&countingPackageResolver{pkg: "com.example"})
	e.reportConnection(&entryLog{}, testFlow(1), ProtocolTCP)
	e.Stop()
	n := 0
	connLogSeen.Range(func(any, any) bool { n++; return true })
	uidPackageCache.Range(func(any, any) bool { n++; return true })
	if n != 0 {
		t.Errorf("%d cache entries survived Stop", n)
	}
}
