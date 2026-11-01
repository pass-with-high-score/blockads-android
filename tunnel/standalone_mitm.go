package tunnel

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"
	"unsafe"

	"github.com/nqmgaming/blockads-tunnel/internal/mitm"
	"golang.org/x/sys/unix"
)

// standaloneMitmServer accepts TCP connections that iptables redirected
// to the local MITM port and hands them to the shared MITM pipeline.
type standaloneMitmServer struct {
	ln        net.Listener
	port      int
	closeOnce sync.Once
}

func (s *standaloneMitmServer) close() {
	s.closeOnce.Do(func() { _ = s.ln.Close() })
}

// StartStandaloneMitm starts a transparent MITM proxy on 127.0.0.1:port
// for root mode. iptables REDIRECT sends the selected browser UIDs'
// HTTP/HTTPS traffic here; SO_ORIGINAL_DST recovers the real destination
// that the userspace TCP stack would otherwise provide in VPN mode.
//
// The CA + filter state is the same one StartStackMitm initialises, so
// cosmetic CSS, scriptlets and the MITM blacklist apply unchanged.
// Returns the CA PEM (empty on error).
func (e *Engine) StartStandaloneMitm(port int, certDir string) string {
	pem := e.StartStackMitm(certDir)
	if pem == "" {
		return ""
	}

	e.mu.Lock()
	certMgr := e.stackCertMgr
	filter := e.stackMitmFilter
	uidr := e.uidResolver
	e.mu.Unlock()

	ln, err := net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		logf("StartStandaloneMitm: listen on 127.0.0.1:%d failed: %v", port, err)
		return ""
	}

	server := &standaloneMitmServer{ln: ln, port: port}

	e.mu.Lock()
	old := e.standaloneMitm
	e.standaloneMitm = server
	e.mu.Unlock()
	if old != nil {
		old.close()
	}

	go server.serve(certMgr, filter, e, uidr)
	logf("Standalone MITM listening on 127.0.0.1:%d", port)
	return pem
}

// StopStandaloneMitm closes the root-mode standalone MITM listener without
// stopping the DNS engine. Safe to call when no listener is running.
func (e *Engine) StopStandaloneMitm() {
	e.mu.Lock()
	old := e.standaloneMitm
	e.standaloneMitm = nil
	e.mu.Unlock()
	if old != nil {
		old.close()
	}
}

func (s *standaloneMitmServer) serve(
	certMgr *mitm.CertManager,
	filter *mitm.MitmFilter,
	blocker mitm.AdBlockChecker,
	uidr mitm.UIDResolver,
) {
	for {
		conn, err := s.ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			logf("Standalone MITM: accept failed: %v", err)
			time.Sleep(100 * time.Millisecond)
			continue
		}
		tcpConn, ok := conn.(*net.TCPConn)
		if !ok {
			_ = conn.Close()
			continue
		}
		dstIP, dstPort, err := originalDst(tcpConn)
		if err != nil {
			logf("Standalone MITM: SO_ORIGINAL_DST failed: %v", err)
			_ = tcpConn.Close()
			continue
		}
		// A direct dial to the listener itself would relay straight back
		// into it; close instead of looping.
		if dstIP.IsLoopback() && dstPort == s.port {
			_ = tcpConn.Close()
			continue
		}
		peer, ok := tcpConn.RemoteAddr().(*net.TCPAddr)
		if !ok {
			_ = tcpConn.Close()
			continue
		}
		flow := mitm.NewFlowID(peer.IP, uint16(peer.Port), dstIP, uint16(dstPort))
		go func(c net.Conn, f mitm.FlowID) {
			defer c.Close()
			mitm.HandleTcpFlow(c, f, certMgr, filter, blocker, uidr, nil)
		}(tcpConn, flow)
	}
}

// originalDst reads SO_ORIGINAL_DST for an IPv4 connection redirected by
// iptables. The kernel fills a sockaddr_in with the destination the app
// originally dialed, before REDIRECT rewrote it to 127.0.0.1.
func originalDst(conn *net.TCPConn) (net.IP, int, error) {
	raw, err := conn.SyscallConn()
	if err != nil {
		return nil, 0, err
	}

	var (
		ip   net.IP
		port int
		serr error
	)
	if err := raw.Control(func(fd uintptr) {
		var sa unix.RawSockaddrInet4
		size := uint32(unix.SizeofSockaddrInet4)
		_, _, errno := unix.Syscall6(
			unix.SYS_GETSOCKOPT,
			fd,
			unix.SOL_IP,
			unix.SO_ORIGINAL_DST,
			uintptr(unsafe.Pointer(&sa)),
			uintptr(unsafe.Pointer(&size)),
			0,
		)
		if errno != 0 {
			serr = errno
			return
		}
		// sockaddr_in.sin_port is network byte order.
		port = int(binary.BigEndian.Uint16((*[2]byte)(unsafe.Pointer(&sa.Port))[:]))
		ip = net.IPv4(sa.Addr[0], sa.Addr[1], sa.Addr[2], sa.Addr[3])
	}); err != nil {
		return nil, 0, err
	}
	if serr != nil {
		return nil, 0, serr
	}
	if ip == nil || port == 0 {
		return nil, 0, fmt.Errorf("empty original destination")
	}
	return ip, port, nil
}
