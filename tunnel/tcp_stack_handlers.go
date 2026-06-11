package tunnel

import (
	"fmt"
	"io"
	"net"
	"sync"
	"syscall"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
)

// ─────────────────────────────────────────────────────────────────────────────
// Phase C default flow handlers for TcpIpStack.
//
// These handlers give the stack an end-to-end packet path without any
// MITM — each terminated TCP/UDP flow is dialed out to the real
// destination via a socket-protected dialer (so it doesn't loop back
// into the VPN) and bytes are relayed. Phase D will replace the TCP
// handler with the real MITM logic for HTTPS flows; the UDP and
// fallback TCP handlers here will remain the non-HTTPS paths.
// ─────────────────────────────────────────────────────────────────────────────

const (
	flowDialTimeout = 10 * time.Second
)

// newProtectedTcpHandler returns a TcpFlowHandler that forwards the
// TCP flow to its real destination with socket protection. protectFn
// may be nil in standalone / non-VPN scenarios.
func newProtectedTcpHandler(uidr UIDResolver, protectFn func(fd int) bool) TcpFlowHandler {
	return func(conn adapter.TCPConn) {
		defer conn.Close()

		flow := tcpFlowID(conn)
		uid := resolveFlowUID(uidr, ProtocolTCP, flow)

		dst := net.JoinHostPort(flow.serverIP.String(), fmt.Sprintf("%d", flow.serverPort))
		dialer := &net.Dialer{
			Timeout: flowDialTimeout,
			Control: protectedControl(protectFn),
		}

		remote, err := dialer.Dial("tcp", dst)
		if err != nil {
			logf("[TcpStack] TCP uid=%d dial %s: %v", uid, dst, err)
			return
		}
		defer remote.Close()

		logf("[TcpStack] TCP uid=%d %s ↔ %s", uid, flow.appIP, dst)

		// Idle deadline on both sides so a stalled flow can't hold
		// stack goroutines forever.
		// No absolute deadline — rely on tun2socks' TCP keepalive
		// (60s idle / 30s interval / 9 probes) to clean up stuck
		// connections. Hard deadlines killed long-lived streams.
		bidiCopyFlow(conn, remote)
	}
}

// newProtectedUdpHandler returns a UdpFlowHandler that proxies the UDP
// flow directly to its destination. For Phase C this is a simple
// echo-style relay — it reads inbound datagrams from the stack,
// forwards them to the real server, and writes responses back.
//
// QUIC (UDP 443) deserves special handling later (either blocked
// per-app or full proxying with DTLS termination); Phase C just
// forwards everything to keep parity with the legacy pipeline.
func newProtectedUdpHandler(uidr UIDResolver, protectFn func(fd int) bool) UdpFlowHandler {
	return func(conn adapter.UDPConn) {
		defer conn.Close()

		flow := udpFlowID(conn)
		uid := resolveFlowUID(uidr, ProtocolUDP, flow)

		dst := &net.UDPAddr{IP: flow.serverIP, Port: flow.serverPort}
		dialer := &net.Dialer{
			Timeout: flowDialTimeout,
			Control: protectedControl(protectFn),
		}
		remote, err := dialer.Dial("udp", dst.String())
		if err != nil {
			logf("[TcpStack] UDP uid=%d dial %s: %v", uid, dst, err)
			return
		}
		defer remote.Close()

		logf("[TcpStack] UDP uid=%d %s ↔ %s", uid, flow.appIP, dst)

		// No absolute deadline — rely on tun2socks' TCP keepalive
		// (60s idle / 30s interval / 9 probes) to clean up stuck
		// connections. Hard deadlines killed long-lived streams.
		bidiCopyFlow(conn, remote)
	}
}

// protectedControl returns a net.Dialer.Control function that invokes
// the VpnService.protect() fd callback before the outbound connection
// is established, ensuring the socket doesn't itself get routed back
// into the VPN. Returns nil when protectFn is nil (standalone mode).
func protectedControl(protectFn func(fd int) bool) func(network, address string, c syscall.RawConn) error {
	if protectFn == nil {
		return nil
	}
	return func(network, address string, c syscall.RawConn) error {
		return c.Control(func(fd uintptr) {
			protectFn(int(fd))
		})
	}
}

// bidiCopyFlow copies bytes in both directions between two net.Conns
// and returns when both directions have finished. Uses TCP half-close
// semantics where available so a FIN on one direction does not abort
// the opposite direction mid-stream (same bug H1 fixed for the legacy
// proxy's bidirectionalCopy).
func bidiCopyFlow(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		io.Copy(b, a)
		if cw, ok := b.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
	}()
	go func() {
		defer wg.Done()
		io.Copy(a, b)
		if cw, ok := a.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
	}()

	wg.Wait()
}
