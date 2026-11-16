package tunnel

import (
	"fmt"
	"net"
	"time"

	"github.com/sagernet/sing/common/control"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

func getOriginalDst(tcpConn *net.TCPConn) (net.IP, int, error) {
	addrPort, err := control.GetOriginalDestination(tcpConn)
	if err != nil {
		return nil, 0, err
	}
	return net.IP(addrPort.Addr().AsSlice()), int(addrPort.Port()), nil
}

type transparentTCPConn struct {
	net.Conn
	id *stack.TransportEndpointID
}

func (c *transparentTCPConn) ID() *stack.TransportEndpointID {
	return c.id
}

func makeTransparentEndpointID(src, dst net.IP, srcPort, dstPort uint16) *stack.TransportEndpointID {
	addr := func(ip net.IP) tcpip.Address {
		if v4 := ip.To4(); v4 != nil {
			return tcpip.AddrFrom4Slice(v4)
		}
		return tcpip.AddrFrom16Slice(ip.To16())
	}
	return &stack.TransportEndpointID{
		LocalAddress:  addr(dst),
		LocalPort:     dstPort,
		RemoteAddress: addr(src),
		RemotePort:    srcPort,
	}
}

type transparentUIDResolver struct {
	base   UIDResolver
	filter *MitmFilter
}

func (r *transparentUIDResolver) ResolveUID(protocol int, localIP string, localPort int, remoteIP string, remotePort int) int {
	if r.base != nil {
		uid := r.base.ResolveUID(protocol, localIP, localPort, remoteIP, remotePort)
		if uid > 0 && (r.filter == nil || r.filter.IsUIDAllowed(uid)) {
			return uid
		}
	}
	if r.filter != nil {
		if firstUID := r.filter.FirstAllowedUID(); firstUID > 0 {
			return firstUID
		}
	}
	return UIDUnknown
}

// StartTransparentMitm starts the transparent TCP MITM proxy on 127.0.0.1:port
func (e *Engine) StartTransparentMitm(port int) error {
	e.mu.Lock()
	if e.standaloneMitmListener != nil {
		e.mu.Unlock()
		return nil
	}

	certMgr := e.stackCertMgr
	filter := e.stackMitmFilter
	blocker := e
	protectFn := e.protectFn
	uidr := &transparentUIDResolver{
		base:   e.uidResolver,
		filter: filter,
	}
	e.mu.Unlock()

	if certMgr == nil || filter == nil {
		return fmt.Errorf("MITM engine not initialized; call StartStackMitm first")
	}

	addr4 := fmt.Sprintf("127.0.0.1:%d", port)
	l4, err := net.Listen("tcp4", addr4)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", addr4, err)
	}

	addr6 := fmt.Sprintf("[::1]:%d", port)
	l6, err6 := net.Listen("tcp6", addr6)
	if err6 != nil {
		logf("Transparent MITM IPv6 listen skipped: %v", err6)
	}

	done := make(chan struct{})

	e.mu.Lock()
	e.standaloneMitmListener = l4
	e.standaloneMitmListener6 = l6
	e.standaloneMitmDone = done
	e.mu.Unlock()

	tcpHandler := newMitmTcpHandler(certMgr, filter, blocker, uidr, protectFn)

	serve := func(l net.Listener) {
		for {
			conn, err := l.Accept()
			if err != nil {
				select {
				case <-done:
					return
				default:
					time.Sleep(50 * time.Millisecond)
					continue
				}
			}

			tcpConn, ok := conn.(*net.TCPConn)
			if !ok {
				conn.Close()
				continue
			}

			go func(c *net.TCPConn) {
				origIP, origPort, err := getOriginalDst(c)
				if err != nil || origPort == 0 {
					logf("Transparent proxy failed to get original dst: %v", err)
					c.Close()
					return
				}

				remoteAddr := c.RemoteAddr().(*net.TCPAddr)
				wrapped := &transparentTCPConn{
					Conn: c,
					id: makeTransparentEndpointID(
						remoteAddr.IP,
						origIP,
						uint16(remoteAddr.Port),
						uint16(origPort),
					),
				}

				tcpHandler(wrapped)
			}(tcpConn)
		}
	}

	go serve(l4)
	if l6 != nil {
		go serve(l6)
		logf("Transparent MITM proxy listening on %s and %s", addr4, addr6)
	} else {
		logf("Transparent MITM proxy listening on %s", addr4)
	}
	return nil
}

// StopTransparentMitm stops the transparent TCP MITM proxy.
func (e *Engine) StopTransparentMitm() {
	e.mu.Lock()
	l4 := e.standaloneMitmListener
	e.standaloneMitmListener = nil
	l6 := e.standaloneMitmListener6
	e.standaloneMitmListener6 = nil
	done := e.standaloneMitmDone
	e.standaloneMitmDone = nil
	e.mu.Unlock()

	if l4 != nil {
		_ = l4.Close()
	}
	if l6 != nil {
		_ = l6.Close()
	}
	if done != nil {
		close(done)
	}
}
