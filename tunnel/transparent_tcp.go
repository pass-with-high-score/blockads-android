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

	addr := fmt.Sprintf("127.0.0.1:%d", port)
	l, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", addr, err)
	}

	done := make(chan struct{})

	e.mu.Lock()
	e.standaloneMitmListener = l
	e.standaloneMitmDone = done
	e.mu.Unlock()

	tcpHandler := newMitmTcpHandler(certMgr, filter, blocker, uidr, protectFn)

	go func() {
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
	}()

	logf("Transparent MITM proxy listening on %s", addr)
	return nil
}

// StopTransparentMitm stops the transparent TCP MITM proxy.
func (e *Engine) StopTransparentMitm() {
	e.mu.Lock()
	l := e.standaloneMitmListener
	e.standaloneMitmListener = nil
	done := e.standaloneMitmDone
	e.standaloneMitmDone = nil
	e.mu.Unlock()

	if l != nil {
		_ = l.Close()
	}
	if done != nil {
		close(done)
	}
}
