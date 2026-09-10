package tunnel

import (
	"io"
	"net"
	"os"
	"strconv"
	"syscall"
	"time"

	"github.com/miekg/dns"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
)

// bufferedTun wraps the TUN file so the gVisor stack can READ it directly
// (inbound packets dispatched with no intermediate queue — this is what
// eliminates the inbound-pipe backpressure deadlock) while its WRITES are
// handed to a background drain goroutine.
//
// Why decouple writes: in direct-TUN mode gVisor writes outbound packets
// on the same goroutine that processes an inbound packet. If tunFile.Write
// blocks (the TUN's kernel buffer fills under load), that goroutine — and
// the whole dispatch — stalls, and the stack stops accepting new flows
// (observed: HandleTCP freezes after a burst). Making Write enqueue-and-
// return (dropping on overflow, which TCP retransmits recover) keeps the
// dispatch path non-blocking so the stack never wedges on a slow TUN.
type bufferedTun struct {
	tun  *os.File
	out  chan []byte
	stop chan struct{}
}

// tunWriteQueueDepth bounds buffered outbound packets. On overflow the
// packet is dropped (TCP retransmits), which is strictly better than
// blocking the gVisor dispatch goroutine.
const tunWriteQueueDepth = 2048

func newBufferedTun(tun *os.File) *bufferedTun {
	b := &bufferedTun{tun: tun, out: make(chan []byte, tunWriteQueueDepth), stop: make(chan struct{})}
	go b.drain()
	return b
}

// Read passes through to the TUN so gVisor dispatches inbound packets
// directly (no queue).
func (b *bufferedTun) Read(p []byte) (int, error) { return b.tun.Read(p) }

// Write never blocks: copy + enqueue, drop on overflow.
func (b *bufferedTun) Write(p []byte) (int, error) {
	pkt := make([]byte, len(p))
	copy(pkt, p)
	select {
	case b.out <- pkt:
	default:
		// queue full — drop; TCP will retransmit.
	}
	return len(p), nil
}

// drain writes queued packets to the real TUN until stopped or a write
// fails (TUN closed).
func (b *bufferedTun) drain() {
	for {
		select {
		case pkt := <-b.out:
			if _, err := b.tun.Write(pkt); err != nil {
				logf("StartFull: TUN drain write error, stopping writer: %v", err)
				return
			}
		case <-b.stop:
			return
		}
	}
}

// halt stops the drain goroutine. Safe to call once.
func (b *bufferedTun) halt() { close(b.stop) }

var _ io.ReadWriter = (*bufferedTun)(nil)

// ─────────────────────────────────────────────────────────────────────────────
// fulltunnel.go — dedicated FULL-NETWORK HTTPS-filtering data path.
//
// This is a separate, isolated engine mode from the DNS-only / WireGuard
// modes. Where the legacy path bridges the TUN into the userspace stack
// through DnsInterceptor + a bounded packetPipe (which deadlocks under
// real browser load — the inbound queue fills, backpressure stalls every
// flow), this path hands the TUN fd DIRECTLY to the gVisor stack, exactly
// as tun2socks is designed to run:
//
//	 apps ─► TUN ─► gVisor stack (owns the fd; native flow control)
//	                 ├─ TCP any        → MITM handler (browser) / passthrough
//	                 ├─ UDP :53        → engine.ServeDNS (adblock + resolve)
//	                 ├─ UDP :443 (br)  → drop (force TCP so HTTP/3 can't dodge MITM)
//	                 └─ UDP other      → protected passthrough
//
// One dispatcher owns the TUN, reads AND writes it, so there is no
// second reader, no pipe, no separate outbound-writer goroutine, and no
// double-hop bottleneck. The legacy Start()/DnsInterceptor/packetPipe
// path is left completely untouched and still serves the current modes;
// StartFull is only entered when the app selects full-network filtering.
// ─────────────────────────────────────────────────────────────────────────────

// StartFull runs the engine in full-network capture mode. The gVisor
// stack reads the TUN fd directly and terminates every flow in userspace.
// StartStackMitm MUST have been called first to initialise the MITM CA +
// filter. Blocks until Stop() is called.
//
// gomobile usage (Kotlin), when full-network HTTPS filtering is on:
//
//	engine.setUseTcpStack(true)          // (informational; not used by StartFull)
//	engine.startStackMitm(certDir)       // CA + filter
//	engine.setMitmAllowedUIDs(uids)
//	engine.startFull(fd, protector)      // instead of engine.start(...)
func (e *Engine) StartFull(fd int, protector SocketProtector) {
	e.mu.Lock()
	if e.running {
		e.mu.Unlock()
		logf("StartFull: engine already running")
		return
	}
	e.running = true
	e.totalQueries.Store(0)
	e.blockedQueries.Store(0)
	// Fresh connection-log dedup set for this session.
	connLogSeen.Range(func(k, _ any) bool { connLogSeen.Delete(k); return true })
	uidPackageCache.Range(func(k, _ any) bool { uidPackageCache.Delete(k); return true })

	var protectFn func(fd int) bool
	if protector != nil {
		protectFn = func(fd int) bool { return protector.Protect(fd) }
	}
	e.protectFn = protectFn
	e.resolver = NewResolver(protectFn)
	e.resolver.Configure(ParseProtocol(e.protocol), e.primaryDNS, e.fallbackDNS, e.dohURL)

	certMgr := e.stackCertMgr
	filter := e.stackMitmFilter
	uidr := e.uidResolver
	done := make(chan struct{})
	e.fullTunnelDone = done
	e.mu.Unlock()

	fail := func(format string, args ...interface{}) {
		logf(format, args...)
		e.mu.Lock()
		e.running = false
		e.fullTunnelDone = nil
		e.mu.Unlock()
	}

	// MITM is OPTIONAL. Full-tunnel mode captures all traffic regardless;
	// HTTPS MITM is a layer on top, active only when StartStackMitm has run
	// (HTTPS filtering enabled). Without it, full-tunnel still gives
	// all-app DNS filtering + per-app firewall + protected passthrough.
	mitmActive := certMgr != nil && filter != nil

	// Own the TUN fd (dup to avoid Android fdsan unique_fd crashes when the
	// ParcelFileDescriptor on the Kotlin side is closed).
	dupFd, err := syscall.Dup(fd)
	if err != nil {
		fail("StartFull: dup TUN fd %d failed: %v", fd, err)
		return
	}
	tunFile := os.NewFile(uintptr(dupFd), "tun")
	if tunFile == nil {
		fail("StartFull: open TUN fd %d failed", dupFd)
		return
	}

	// Build the stack reading/writing the TUN DIRECTLY — no interceptor,
	// no packetPipe, no outbound-writer goroutine.
	stack := NewTcpIpStack()
	stack.SetUIDResolver(uidr)
	if mitmActive {
		// HTTPS filtering on → MITM browser TCP, adblock, cosmetic inject.
		stack.SetTcpHandler(newMitmTcpHandler(certMgr, filter, e, uidr, protectFn))
	} else {
		// Full-tunnel without HTTPS → protected passthrough (DNS-level filter).
		stack.SetTcpHandler(newFullPassthroughTcpHandler(e, uidr, protectFn))
	}
	stack.SetUdpHandler(newFullTunnelUdpHandler(e, filter, uidr, protectFn))
	logf("StartFull: mitm=%t", mitmActive)

	// gVisor reads the TUN directly (no inbound queue) but writes go
	// through an async drain so a slow TUN can't block the dispatch path.
	btun := newBufferedTun(tunFile)

	e.mu.Lock()
	e.tunFile = tunFile
	e.tcpStack = stack
	e.mu.Unlock()

	if err := stack.Start(btun, uint32(defaultTunMTU)); err != nil {
		btun.halt()
		tunFile.Close()
		e.mu.Lock()
		e.tcpStack = nil
		e.tunFile = nil
		e.mu.Unlock()
		fail("StartFull: stack start failed: %v", err)
		return
	}

	logf("StartFull: full-network stack running (direct TUN read, async TUN write, mtu=%d)", defaultTunMTU)

	// Block until Stop() closes done. gVisor's dispatcher reads the TUN on
	// its own goroutine; the drain goroutine handles writes.
	<-done
	btun.halt()
	logf("StartFull: stopped")
}

// newFullTunnelUdpHandler routes UDP flows for full-network mode: DNS
// (port 53) is answered locally via engine.ServeDNS (the same adblock +
// resolve pipeline used in standalone/root mode); everything else falls
// through to the MITM-aware UDP handler (browser QUIC suppression +
// protected passthrough).
func newFullTunnelUdpHandler(engine *Engine, filter *MitmFilter, uidr UIDResolver, protectFn func(fd int) bool) UdpFlowHandler {
	relay := newProtectedUdpHandler(uidr, protectFn)
	return func(conn adapter.UDPConn) {
		flow := udpFlowID(conn)
		// DNS → answer locally (adblock + resolve).
		if flow.serverPort == 53 {
			handleDNSOverUDP(conn, engine)
			return
		}
		engine.logConnection(flow, ProtocolUDP)
		// Browser QUIC (UDP 443): drop to force TCP TLS for MITM — ONLY
		// when HTTP/3 filtering is enabled from the UI. Default off →
		// relay QUIC so pages load fully. DNS-level blocking still applies
		// either way.
		if engine.quicDrop.Load() && flow.serverPort == 443 && filter != nil && filter.HasAllowedUIDs() {
			uid := resolveFlowUID(uidr, ProtocolUDP, flow)
			if uid != UIDUnknown && filter.IsUIDAllowed(uid) {
				_ = conn.Close()
				return
			}
		}
		relay(conn)
	}
}

// newFullPassthroughTcpHandler is the full-tunnel TCP handler used when
// HTTPS MITM is OFF: it passes every flow through a socket-protected dialer
// (private/loopback dialed directly so LAN stays reachable). DNS-level
// ad-blocking + firewall still apply via the DNS handler (ServeDNS).
//
// NOTE: a per-connection firewall (resolving the owning app per flow and
// dropping firewalled apps' connections) was tried here but calling the
// gomobile AppResolver JNI from this hot, highly-concurrent flow path
// panics under Go's cgocheck ("Go pointer to unpinned Go pointer"). Until
// a pointer-safe / UID-based path exists, firewall enforcement stays at the
// DNS layer (a firewalled app can't resolve names, so it can't connect).
func newFullPassthroughTcpHandler(engine *Engine, uidr UIDResolver, protectFn func(fd int) bool) TcpFlowHandler {
	return func(conn adapter.TCPConn) {
		defer conn.Close()
		flow := tcpFlowID(conn)
		engine.logConnection(flow, ProtocolTCP)
		relayDirectFromFlow(conn, flow, engine, protectFn)
	}
}

func relayDirectFromFlow(clientConn net.Conn, flow flowID, engine *Engine, protectFn func(fd int) bool) {
	dialer := &net.Dialer{
		Timeout: flowDialTimeout,
		Control: protectedControl(protectFn),
	}
	dst := net.JoinHostPort(flow.serverIP.String(), strconv.Itoa(flow.serverPort))
	remote, err := dialer.Dial("tcp", dst)
	if err != nil {
		logf("[FullTunnel] upstream dial %s failed: %v", dst, err)
		return
	}
	defer remote.Close()

	bidiCopyFlow(clientConn, remote)
}

// SetFilterHttp3 toggles HTTP/3 (QUIC) filtering. When true, browser QUIC
// is dropped to force filterable TCP TLS (max in-page filtering, but some
// sites may load partially). When false (default), QUIC is relayed so
// pages load fully. Safe to call at any time; takes effect for new flows.
func (e *Engine) SetFilterHttp3(enabled bool) {
	e.quicDrop.Store(enabled)
	logf("SetFilterHttp3: HTTP/3 (QUIC) filtering = %t", enabled)
}

// dnsUDPIdleTimeout bounds how long a DNS UDP flow is kept open waiting
// for another query on the same 5-tuple before the handler goroutine
// exits. Most resolvers use a fresh source port per query (one query per
// flow), so this mainly reaps idle handlers promptly.
const dnsUDPIdleTimeout = 15 * time.Second

// handleDNSOverUDP reads DNS query datagrams off a stack UDP flow, runs
// each through engine.ServeDNS, and writes the packed response back to
// the app. Runs on its own goroutine per flow.
func handleDNSOverUDP(conn adapter.UDPConn, engine *Engine) {
	defer conn.Close()
	// Attribute DNS to the owning app (UID→package) so the log shows the
	// real app instead of "RootProxy". Resolved once per flow.
	appName := engine.appNameForFlow(udpFlowID(conn), ProtocolUDP)
	buf := make([]byte, 4096) // ample for a UDP DNS query (EDNS bufsize ≤ 4096)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(dnsUDPIdleTimeout))
		n, err := conn.Read(buf)
		if err != nil {
			return
		}
		req := new(dns.Msg)
		if err := req.Unpack(buf[:n]); err != nil {
			continue // not a parseable DNS message; ignore
		}
		engine.serveDNS(&udpDNSResponseWriter{conn: conn}, req, appName)
	}
}

// udpDNSResponseWriter adapts a stack UDP flow to dns.ResponseWriter so
// engine.ServeDNS can reply on it. Only the methods ServeDNS actually
// uses (WriteMsg, RemoteAddr) do real work; the rest are minimal
// conformance stubs.
type udpDNSResponseWriter struct {
	conn adapter.UDPConn
}

func (w *udpDNSResponseWriter) LocalAddr() net.Addr  { return w.conn.LocalAddr() }
func (w *udpDNSResponseWriter) RemoteAddr() net.Addr { return w.conn.RemoteAddr() }

func (w *udpDNSResponseWriter) WriteMsg(m *dns.Msg) error {
	packed, err := m.Pack()
	if err != nil {
		return err
	}
	_, err = w.conn.Write(packed)
	return err
}

func (w *udpDNSResponseWriter) Write(b []byte) (int, error) { return w.conn.Write(b) }

// Close is a no-op: the owning handleDNSOverUDP loop owns the conn and
// closes it when the flow ends.
func (w *udpDNSResponseWriter) Close() error   { return nil }
func (w *udpDNSResponseWriter) TsigStatus() error { return nil }
func (w *udpDNSResponseWriter) TsigTimersOnly(bool) {}
func (w *udpDNSResponseWriter) Hijack()             {}
