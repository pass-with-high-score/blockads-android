package tunnel

import (
	"fmt"
	"io"
	"sync"
	"sync/atomic"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/core/device/iobased"
	"github.com/xjasonlyu/tun2socks/v2/core/option"
	gvisorStack "gvisor.dev/gvisor/pkg/tcpip/stack"
)

// ─────────────────────────────────────────────────────────────────────────────
// TcpIpStack — userspace TCP/IP stack backed by gVisor via tun2socks.
//
// This is Phase A of the AdGuard-style refactor. The stack terminates
// every TCP/UDP flow that enters the TUN device in userspace and hands
// each connection to the registered flow handler. Unlike the system
// HTTP proxy approach used by the legacy MitmProxy, this model sees the
// real 5-tuple (src IP:port → dst IP:port) on every connection, which
// lets us look up the owning app UID via Android's
// ConnectivityManager.getConnectionOwnerUid() API. That UID visibility
// is the architectural unlock that allows per-app scoping of HTTPS
// filtering — the capability AdGuard uses to MITM only browsers.
//
// In Phase A the stack is created and flows are logged; it is not yet
// wired into the engine. Later phases will attach a MITM handler.
// ─────────────────────────────────────────────────────────────────────────────

// TcpFlowHandler is invoked on its own goroutine for every TCP
// connection terminated by the stack. Implementations are expected to
// own the connection for its full lifetime — read/write as needed,
// then Close. Returning from the handler ends the connection's
// processing; the stack does not dispatch the same conn twice.
//
// The provided conn carries the 5-tuple via conn.ID(): LocalAddress
// and LocalPort are the original TUN destination (the real remote
// server the app was trying to reach); RemoteAddress and RemotePort
// are the source (the app's ephemeral socket). The conn's Write and
// Read are relative to the stack — writing sends bytes back to the
// app, reading consumes bytes from the app.
type TcpFlowHandler func(conn adapter.TCPConn)

// UdpFlowHandler is invoked on its own goroutine for every UDP flow.
// Same ownership semantics as TcpFlowHandler — the handler runs for
// the flow's lifetime and must Close() when finished.
type UdpFlowHandler func(conn adapter.UDPConn)

// TcpIpStack wraps the gVisor-backed userspace TCP/IP stack provided by
// tun2socks. A single instance manages one TUN file descriptor and
// dispatches every terminated flow to the registered handlers.
type TcpIpStack struct {
	mu       sync.Mutex
	stack    *gvisorStack.Stack
	endpoint *iobased.Endpoint
	running  atomic.Bool

	tcpHandler TcpFlowHandler
	udpHandler UdpFlowHandler
	uidr       UIDResolver

	// stats
	tcpFlows atomic.Int64
	udpFlows atomic.Int64
}

// NewTcpIpStack creates an unconfigured stack. Call Start to begin
// processing packets from a TUN file descriptor.
func NewTcpIpStack() *TcpIpStack {
	return &TcpIpStack{}
}

// SetTcpHandler registers the handler invoked for each new TCP connection.
// Must be called before Start. If nil, TCP connections are immediately
// closed.
func (s *TcpIpStack) SetTcpHandler(h TcpFlowHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tcpHandler = h
}

// SetUdpHandler registers the handler invoked for each new UDP flow.
// Must be called before Start. If nil, UDP flows are immediately closed.
func (s *TcpIpStack) SetUdpHandler(h UdpFlowHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.udpHandler = h
}

// SetUIDResolver registers the resolver used to look up the owning app
// UID for each flow. May be nil (falls back to UIDUnknown for every
// flow). Typically wired from Kotlin via Engine.SetUIDResolver.
func (s *TcpIpStack) SetUIDResolver(r UIDResolver) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.uidr = r
}

// Start constructs the gVisor stack on top of the supplied ReadWriter
// and begins processing packets. Read is expected to return one IP
// packet per call (up to mtu bytes); Write receives one IP packet per
// call. Callers provide whatever ReadWriter suits their scenario —
// a real TUN fd wrapped with os.NewFile, or a packet-pipe adapter for
// parallel-mode operation alongside an existing TUN reader.
//
// Start does not take ownership of the ReadWriter. Stop tears down the
// stack only; closing the underlying fd or pipe is the caller's
// responsibility.
func (s *TcpIpStack) Start(rw io.ReadWriter, mtu uint32) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running.Load() {
		return fmt.Errorf("tcp/ip stack already running")
	}

	ep, err := iobased.New(rw, mtu, 0)
	if err != nil {
		return fmt.Errorf("create iobased endpoint: %w", err)
	}

	st, err := core.CreateStack(&core.Config{
		LinkEndpoint:     ep,
		TransportHandler: s,
		Options:          []option.Option{},
	})
	if err != nil {
		return fmt.Errorf("create stack: %w", err)
	}

	s.endpoint = ep
	s.stack = st
	s.running.Store(true)

	logf("TcpIpStack: started (mtu=%d)", mtu)
	return nil
}

// Stop tears down the stack and closes the underlying fd. Safe to call
// multiple times.
//
// Known issue (to revisit in Phase E with real traffic): gVisor
// dispatch goroutines may still be reading from the device when
// stack.Close() races with device.Close(). tun2socks' own examples
// close the LinkEndpoint first to stop ingress, then drain, then
// close the stack. Revisit once Phase C reveals whether Android
// exhibits this race.
func (s *TcpIpStack) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running.Load() {
		return
	}
	s.running.Store(false)

	if s.stack != nil {
		s.stack.Close()
		s.stack = nil
	}
	s.endpoint = nil
	logf("TcpIpStack: stopped (tcp=%d udp=%d flows handled)", s.tcpFlows.Load(), s.udpFlows.Load())
}

// IsRunning reports whether the stack is currently processing packets.
func (s *TcpIpStack) IsRunning() bool { return s.running.Load() }

// TcpFlowCount returns the total number of TCP flows dispatched.
func (s *TcpIpStack) TcpFlowCount() int64 { return s.tcpFlows.Load() }

// UdpFlowCount returns the total number of UDP flows dispatched.
func (s *TcpIpStack) UdpFlowCount() int64 { return s.udpFlows.Load() }

// HandleTCP implements adapter.TransportHandler. Invoked by gVisor for
// every terminated TCP connection.
func (s *TcpIpStack) HandleTCP(conn adapter.TCPConn) {
	c := s.tcpFlows.Add(1)

	s.mu.Lock()
	h := s.tcpHandler
	uidr := s.uidr
	s.mu.Unlock()

	flow := tcpFlowID(conn)
	uid := resolveFlowUID(uidr, ProtocolTCP, flow)

	if c <= 5 {
		logf("TcpIpStack: HandleTCP #%d uid=%d %s:%d → %s:%d (handler set: %t)",
			c, uid, flow.appIP, flow.appPort, flow.serverIP, flow.serverPort, h != nil)
	}

	if h == nil {
		// Phase A/B default: log the 5-tuple + UID, drop the connection.
		logf("TcpIpStack: TCP uid=%d %s:%d → %s:%d (no handler, dropping)",
			uid, flow.appIP, flow.appPort, flow.serverIP, flow.serverPort)
		_ = conn.Close()
		return
	}
	h(conn)
}

// HandleUDP implements adapter.TransportHandler. Invoked for every UDP flow.
func (s *TcpIpStack) HandleUDP(conn adapter.UDPConn) {
	s.udpFlows.Add(1)

	s.mu.Lock()
	h := s.udpHandler
	uidr := s.uidr
	s.mu.Unlock()

	flow := udpFlowID(conn)
	uid := resolveFlowUID(uidr, ProtocolUDP, flow)

	if h == nil {
		logf("TcpIpStack: UDP uid=%d %s:%d → %s:%d (no handler, dropping)",
			uid, flow.appIP, flow.appPort, flow.serverIP, flow.serverPort)
		_ = conn.Close()
		return
	}
	h(conn)
}

// Compile-time assertion that TcpIpStack implements the tun2socks
// transport handler interface.
var _ adapter.TransportHandler = (*TcpIpStack)(nil)
