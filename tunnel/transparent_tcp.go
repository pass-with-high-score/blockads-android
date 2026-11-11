package tunnel

import (
	"fmt"
	"net"
	"syscall"
	"time"
	"unsafe"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type sockaddrIn struct {
	sinFamily uint16
	sinPort   uint16
	sinAddr   [4]byte
	sinZero   [8]byte
}

type sockaddrIn6 struct {
	sin6Family   uint16
	sin6Port     uint16
	sin6Flowinfo uint32
	sin6Addr     [16]byte
	sin6ScopeID  uint32
}

func getOriginalDst(tcpConn *net.TCPConn) (net.IP, int, error) {
	raw, err := tcpConn.SyscallConn()
	if err != nil {
		return nil, 0, err
	}
	var origIP net.IP
	var origPort int
	var opErr error

	err = raw.Control(func(fd uintptr) {
		// Try IPv4 SO_ORIGINAL_DST
		var addr4 sockaddrIn
		size4 := uint32(unsafe.Sizeof(addr4))
		_, _, errno := syscall.Syscall6(
			syscall.SYS_GETSOCKOPT,
			fd,
			uintptr(syscall.IPPROTO_IP),
			uintptr(80), // SO_ORIGINAL_DST
			uintptr(unsafe.Pointer(&addr4)),
			uintptr(unsafe.Pointer(&size4)),
			0,
		)
		if errno == 0 {
			origIP = net.IPv4(addr4.sinAddr[0], addr4.sinAddr[1], addr4.sinAddr[2], addr4.sinAddr[3])
			origPort = int(addr4.sinPort>>8) | int(addr4.sinPort&0xff)<<8
			return
		}

		// Try IPv6 IP6T_SO_ORIGINAL_DST
		var addr6 sockaddrIn6
		size6 := uint32(unsafe.Sizeof(addr6))
		_, _, errno6 := syscall.Syscall6(
			syscall.SYS_GETSOCKOPT,
			fd,
			uintptr(syscall.IPPROTO_IPV6),
			uintptr(80), // IP6T_SO_ORIGINAL_DST
			uintptr(unsafe.Pointer(&addr6)),
			uintptr(unsafe.Pointer(&size6)),
			0,
		)
		if errno6 == 0 {
			origIP = net.IP(addr6.sin6Addr[:])
			origPort = int(addr6.sin6Port>>8) | int(addr6.sin6Port&0xff)<<8
			return
		}

		opErr = errno
	})

	if err != nil {
		return nil, 0, err
	}
	if opErr != nil {
		return nil, 0, opErr
	}
	return origIP, origPort, nil
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
