package tailscale

import (
	"context"
	"io"
	"net"
	"net/netip"
	"strconv"
	"syscall"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// SetExitDNSHandler registers a custom DNS wire-format handler for Exit Node /dns-query requests.
func (dp *DataPlane) SetExitDNSHandler(fn func(context.Context, string, []byte) ([]byte, error)) {
	dp.mu.Lock()
	dp.exitDNSHandler = fn
	dp.mu.Unlock()
}

func (dp *DataPlane) getExitDNSHandler() func(context.Context, string, []byte) ([]byte, error) {
	dp.mu.Lock()
	fn := dp.exitDNSHandler
	dp.mu.Unlock()
	return fn
}

func (dp *DataPlane) installExitForwarders() {
	if dp.netStack == nil {
		return
	}

	tcpFwd := tcp.NewForwarder(dp.netStack, 0, 256, func(r *tcp.ForwarderRequest) {
		dp.handleExitTCP(r)
	})
	dp.netStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)

	udpFwd := udp.NewForwarder(dp.netStack, func(r *udp.ForwarderRequest) {
		dp.handleExitUDP(r)
	})
	dp.netStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)
}

func (dp *DataPlane) isLocalOrDisabled(addrBytes []byte) (netip.Addr, bool) {
	if !dp.advertiseExit.Load() {
		return netip.Addr{}, false
	}
	addr, ok := netip.AddrFromSlice(addrBytes)
	if !ok {
		return netip.Addr{}, false
	}
	addr = addr.Unmap()
	dp.mu.Lock()
	isLocal := dp.assignedAddrs[addr]
	dp.mu.Unlock()
	if isLocal {
		return addr, false
	}
	return addr, true
}

func (dp *DataPlane) protectedDialer(timeout time.Duration) *net.Dialer {
	dp.mu.Lock()
	protect := dp.protectFn
	dp.mu.Unlock()

	return &net.Dialer{
		Timeout: timeout,
		Control: func(_, _ string, c syscall.RawConn) error {
			if protect == nil {
				return nil
			}
			var ctrlErr error
			if err := c.Control(func(fd uintptr) {
				if !protect(int(fd)) {
					ctrlErr = context.DeadlineExceeded
				}
			}); err != nil {
				return err
			}
			return ctrlErr
		},
	}
}

func (dp *DataPlane) handleExitTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dstAddr, ok := dp.isLocalOrDisabled(id.LocalAddress.AsSlice())
	if !ok {
		r.Complete(true)
		return
	}

	target := net.JoinHostPort(dstAddr.String(), strconv.Itoa(int(id.LocalPort)))
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	outConn, err := dp.protectedDialer(15 * time.Second).DialContext(ctx, "tcp", target)
	cancel()
	if err != nil {
		r.Complete(true)
		return
	}

	var wq waiter.Queue
	ep, tcpErr := r.CreateEndpoint(&wq)
	if tcpErr != nil {
		_ = outConn.Close()
		r.Complete(true)
		return
	}
	r.Complete(false)

	inConn := gonet.NewTCPConn(&wq, ep)
	go func() {
		defer inConn.Close()
		defer outConn.Close()
		go func() {
			_, _ = io.Copy(outConn, inConn)
			_ = outConn.Close()
		}()
		_, _ = io.Copy(inConn, outConn)
	}()
}

func (dp *DataPlane) handleExitUDP(r *udp.ForwarderRequest) {
	id := r.ID()
	dstAddr, ok := dp.isLocalOrDisabled(id.LocalAddress.AsSlice())
	if !ok {
		return
	}

	var wq waiter.Queue
	ep, udpErr := r.CreateEndpoint(&wq)
	if udpErr != nil {
		return
	}
	inConn := gonet.NewUDPConn(&wq, ep)

	target := net.JoinHostPort(dstAddr.String(), strconv.Itoa(int(id.LocalPort)))
	outConn, err := dp.protectedDialer(10 * time.Second).Dial("udp", target)
	if err != nil {
		_ = inConn.Close()
		return
	}

	go func() {
		defer inConn.Close()
		defer outConn.Close()
		go func() {
			buf := make([]byte, 4096)
			for {
				_ = inConn.SetReadDeadline(time.Now().Add(60 * time.Second))
				n, readErr := inConn.Read(buf)
				if n > 0 {
					if _, writeErr := outConn.Write(buf[:n]); writeErr != nil {
						return
					}
				}
				if readErr != nil {
					return
				}
			}
		}()
		buf := make([]byte, 4096)
		for {
			_ = outConn.SetReadDeadline(time.Now().Add(60 * time.Second))
			n, readErr := outConn.Read(buf)
			if n > 0 {
				if _, writeErr := inConn.Write(buf[:n]); writeErr != nil {
					return
				}
			}
			if readErr != nil {
				return
			}
		}
	}()
}

// SetAdvertiseExitNode enables or disables forwarding peer internet traffic out of this device.
func (dp *DataPlane) SetAdvertiseExitNode(enabled bool) {
	dp.advertiseExit.Store(enabled)
	if dp.netStack == nil {
		return
	}
	_ = dp.netStack.SetPromiscuousMode(tsNicID, enabled)
	_ = dp.netStack.SetSpoofing(tsNicID, enabled)
}
