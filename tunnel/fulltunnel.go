package tunnel

import (
	"encoding/binary"
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

// StartFull runs the engine in full-network capture mode. The gVisor
// stack reads the TUN fd directly and terminates every flow in userspace.
// Blocks until Stop() is called.
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
	e.resolver.SetODoHRelay(e.odohRelayURL)
	e.resolver.SetProxyDialer(e.DialProxy)

	certMgr := e.stackCertMgr
	filter := e.stackMitmFilter
	uidr := e.uidResolver
	done := make(chan struct{})
	e.fullTunnelDone = done

	// Ensure active proxy client uses the live protectFn
	if pc := e.proxyClient.Load(); pc != nil && *pc != nil {
		(*pc).SetProtectFn(protectFn)
		logf("StartFull: Proxy outbound (%s) active via %s", (*pc).Protocol(), (*pc).ServerAddress())
	}
	if client := e.ssClient.Load(); client != nil && client.Config() != nil {
		client.SetProtectFn(protectFn)
		logf("StartFull: Shadowsocks outbound active via %s", client.ServerAddress())
	}
	if mgr := e.tsManager.Load(); mgr != nil {
		mgr.SetProtectFn(protectFn)
		_ = mgr.StartSession("blockads-android")
	}
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
	if err := syscall.SetNonblock(dupFd, true); err != nil {
		logf("StartFull: set nonblock on dup TUN fd %d: %v", dupFd, err)
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
	var tcpHandler TcpFlowHandler
	if mitmActive {
		tcpHandler = newMitmTcpHandler(certMgr, filter, e, uidr, protectFn)
	} else {
		tcpHandler = newFullPassthroughTcpHandler(e, uidr, protectFn)
	}
	stack.SetTcpHandler(func(conn adapter.TCPConn) {
		flow := tcpFlowID(conn)
		if flow.serverPort == 53 {
			handleDNSOverTCP(conn, e)
			return
		}
		if e.isFlowFirewalled(flow, ProtocolTCP) {
			_ = conn.Close()
			return
		}
		tcpHandler(conn)
	})
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
		if engine.isFlowFirewalled(flow, ProtocolUDP) {
			_ = conn.Close()
			return
		}
		// Gate -1.6: Ruleset IP rules (e.g. IP-CIDR, REJECT)
		if engine.IsDomainBlocked(flow.serverIP.String()) {
			_ = conn.Close()
			return
		}
		// Gate -1.7: WebRTC STUN leak protection (ports 3478, 5349, 19302-19309)
		if engine.blockWebRTC.Load() && isStunPort(flow.serverPort) {
			_ = conn.Close()
			return
		}
		engine.logConnection(flow, ProtocolUDP)

		// When Proxy (Shadowsocks, Trojan, VLESS) or Tailscale peer routing is active, drop QUIC (UDP 443)
		// so browsers fall back to TCP through the tunnel.
		if (engine.proxyClient.Load() != nil || engine.ssClient.Load() != nil || engine.matchTailscalePeer(flow.serverIP)) && flow.serverPort == 443 {
			_ = conn.Close()
			return
		}

		if udpRemote, used, err := engine.dialTailscaleUDP(flow.serverIP, flow.serverPort); used {
			defer conn.Close()
			if err != nil {
				return
			}
			defer udpRemote.Close()
			bidiCopyFlow(conn, udpRemote)
			return
		}

		// Browser QUIC (UDP 443): drop to force TCP TLS for MITM — ONLY
		// when MITM is active and HTTP/3 filtering is enabled from the UI.
		// Default off → relay QUIC so pages load fully. DNS-level blocking
		// still applies either way.
		if engine.quicDrop.Load() && filter != nil && filter.HasAllowedUIDs() && flow.serverPort == 443 {
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
		// Gate -1: DoT (port 853) - close to force fallback to port 53 DNS
		if flow.serverPort == 853 {
			return
		}
		if isVirtualDnsIP(flow.serverIP) {
			return
		}
		// Gate -1.5: Hardcoded DoH Direct-IP (port 443) - close to force fallback if DoH/DoT blocking is enabled
		if engine.IsDoHBlockingEnabled() && flow.serverPort == 443 && isKnownPublicDoHIP(flow.serverIP) {
			return
		}
		// Gate -1.6: Ruleset IP rules (e.g. IP-CIDR, REJECT)
		if engine.IsDomainBlocked(flow.serverIP.String()) {
			return
		}
		// Gate -1.7: WebRTC STUN/TURN over TCP (ports 3478, 5349)
		if engine.blockWebRTC.Load() && (flow.serverPort == 3478 || flow.serverPort == 5349) {
			return
		}
		engine.logConnection(flow, ProtocolTCP)
		relayDirectFromFlow(conn, flow, engine, protectFn)
	}
}

// isStunPort returns true if the port is commonly used for WebRTC STUN discovery
// (RFC 5389 / Google STUN infrastructure).
func isStunPort(port int) bool {
	return port == 3478 || port == 5349 || (port >= 19302 && port <= 19309)
}

func relayDirectFromFlow(clientConn net.Conn, flow flowID, engine *Engine, protectFn func(fd int) bool) {
	if remote, used, err := engine.DialProxy(flow.serverIP, flow.serverPort); used {
		if err != nil {
			logf("[FullTunnel] proxy dial %s:%d failed: %v", flow.serverIP, flow.serverPort, err)
			return
		}
		defer remote.Close()
		bidiCopyFlow(clientConn, remote)
		return
	}

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

func (w *udpDNSResponseWriter) Close() error        { return nil }
func (w *udpDNSResponseWriter) TsigStatus() error    { return nil }
func (w *udpDNSResponseWriter) TsigTimersOnly(bool) {}
func (w *udpDNSResponseWriter) Hijack()             {}

// isVirtualDnsIP checks whether the destination IP matches any of our virtual DNS server addresses.
func isVirtualDnsIP(ip net.IP) bool {
	if ip == nil {
		return false
	}
	s := ip.String()
	return s == "100.64.100.1" || s == "fd00::1" || s == "192.0.2.2" || s == "2001:db8:1111::2"
}

// handleDNSOverTCP reads DNS query frames off a stack TCP flow (RFC 7766), runs
// each through engine.ServeDNS, and writes the framed response back.
func handleDNSOverTCP(conn adapter.TCPConn, engine *Engine) {
	defer conn.Close()
	appName := engine.appNameForFlow(tcpFlowID(conn), ProtocolTCP)
	lenBuf := make([]byte, 2)
	for {
		_ = conn.SetReadDeadline(time.Now().Add(dnsUDPIdleTimeout))
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return
		}
		msgLen := binary.BigEndian.Uint16(lenBuf)
		if msgLen == 0 {
			return
		}
		buf := make([]byte, msgLen)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return
		}
		req := new(dns.Msg)
		if err := req.Unpack(buf); err != nil {
			continue
		}
		engine.serveDNS(&tcpDNSResponseWriter{conn: conn}, req, appName)
	}
}

// tcpDNSResponseWriter adapts a stack TCP flow to dns.ResponseWriter with 2-byte framing.
type tcpDNSResponseWriter struct {
	conn adapter.TCPConn
}

func (w *tcpDNSResponseWriter) LocalAddr() net.Addr  { return w.conn.LocalAddr() }
func (w *tcpDNSResponseWriter) RemoteAddr() net.Addr { return w.conn.RemoteAddr() }

func (w *tcpDNSResponseWriter) WriteMsg(m *dns.Msg) error {
	packed, err := m.Pack()
	if err != nil {
		return err
	}
	lenBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBuf, uint16(len(packed)))
	if _, err := w.conn.Write(lenBuf); err != nil {
		return err
	}
	_, err = w.conn.Write(packed)
	return err
}

func (w *tcpDNSResponseWriter) Write(b []byte) (int, error) {
	lenBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBuf, uint16(len(b)))
	if _, err := w.conn.Write(lenBuf); err != nil {
		return 0, err
	}
	return w.conn.Write(b)
}

func (w *tcpDNSResponseWriter) Close() error        { return nil }
func (w *tcpDNSResponseWriter) TsigStatus() error    { return nil }
func (w *tcpDNSResponseWriter) TsigTimersOnly(bool) {}
func (w *tcpDNSResponseWriter) Hijack()             {}

