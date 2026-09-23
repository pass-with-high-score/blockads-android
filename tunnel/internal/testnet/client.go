//go:build linux

// Package testnet is a test-only netstack harness for the tunnel engine.
//
// The engine's TUN is one end of a socketpair(AF_UNIX, SOCK_DGRAM), which
// keeps IP packet boundaries. The other end is driven by a Client: a gVisor
// userspace stack that plays the role of the apps behind the VPN, so tests
// can dial TCP and UDP through the engine with ordinary net.Conn values.
//
// Nothing in the production build imports this package.
package testnet

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"sync"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// Addresses the client stack owns. They mirror the VPN interface addresses
// the app assigns on Android.
var (
	ClientV4 = netip.MustParseAddr("10.0.0.2")
	ClientV6 = netip.MustParseAddr("fd00::2")
)

const (
	nicID = 1
	mtu   = 1500
)

// Socketpair returns a datagram socketpair. engineFd is meant for
// Engine.Start/StartFull (which dup it); the caller must close it with
// unix.Close. client is the other end, non-blocking so reads honor
// deadlines and Close unblocks them.
func Socketpair() (engineFd int, client *os.File, err error) {
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return -1, nil, err
	}
	if err := unix.SetNonblock(fds[1], true); err != nil {
		unix.Close(fds[0])
		unix.Close(fds[1])
		return -1, nil, err
	}
	return fds[0], os.NewFile(uintptr(fds[1]), "tun-client"), nil
}

// Client is a gVisor stack whose only NIC is wired to a TUN socket.
type Client struct {
	s      *stack.Stack
	ep     *channel.Endpoint
	f      *os.File
	cancel context.CancelFunc
	wg     sync.WaitGroup
	once   sync.Once
}

// NewClient starts a client stack on f and takes ownership of it.
func NewClient(f *os.File) (*Client, error) {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6},
	})
	ep := channel.New(1024, mtu, "")
	if err := s.CreateNIC(nicID, ep); err != nil {
		s.Close()
		return nil, fmt.Errorf("create NIC: %s", err)
	}
	for _, a := range []netip.Addr{ClientV4, ClientV6} {
		pa := tcpip.ProtocolAddress{
			Protocol:          protoOf(a),
			AddressWithPrefix: tcpip.AddrFromSlice(a.AsSlice()).WithPrefix(),
		}
		if err := s.AddProtocolAddress(nicID, pa, stack.AddressProperties{}); err != nil {
			s.Close()
			return nil, fmt.Errorf("add address %s: %s", a, err)
		}
	}
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})

	ctx, cancel := context.WithCancel(context.Background())
	c := &Client{s: s, ep: ep, f: f, cancel: cancel}
	c.wg.Add(2)
	go c.pumpOut(ctx)
	go c.pumpIn()
	return c, nil
}

// pumpOut writes every packet the client stack emits to the TUN socket.
func (c *Client) pumpOut(ctx context.Context) {
	defer c.wg.Done()
	for {
		pkt := c.ep.ReadContext(ctx)
		if pkt == nil {
			return
		}
		v := pkt.ToView()
		_, err := c.f.Write(v.AsSlice())
		v.Release()
		pkt.DecRef()
		if err != nil {
			return
		}
	}
}

// pumpIn injects every packet read from the TUN socket into the stack.
func (c *Client) pumpIn() {
	defer c.wg.Done()
	buf := make([]byte, 65536)
	for {
		n, err := c.f.Read(buf)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		var proto tcpip.NetworkProtocolNumber
		switch buf[0] >> 4 {
		case 4:
			proto = ipv4.ProtocolNumber
		case 6:
			proto = ipv6.ProtocolNumber
		default:
			continue
		}
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(append([]byte(nil), buf[:n]...)),
		})
		c.ep.InjectInbound(proto, pkt)
		pkt.DecRef()
	}
}

// DialTCP opens a TCP connection from the client stack through the TUN.
func (c *Client) DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	return gonet.DialContextTCP(ctx, c.s, fullAddr(dst), protoOf(dst.Addr()))
}

// DialUDP opens a connected UDP socket from the client stack through the TUN.
func (c *Client) DialUDP(dst netip.AddrPort) (net.Conn, error) {
	ra := fullAddr(dst)
	return gonet.DialUDP(c.s, nil, &ra, protoOf(dst.Addr()))
}

// Close stops the pumps, closes the TUN socket and tears the stack down.
// Safe to call more than once.
func (c *Client) Close() {
	c.once.Do(func() {
		c.cancel()
		c.f.Close()
		c.wg.Wait()
		c.s.Close()
		c.s.Wait()
		c.ep.Close()
	})
}

func fullAddr(ap netip.AddrPort) tcpip.FullAddress {
	return tcpip.FullAddress{NIC: nicID, Addr: tcpip.AddrFromSlice(ap.Addr().AsSlice()), Port: ap.Port()}
}

func protoOf(a netip.Addr) tcpip.NetworkProtocolNumber {
	if a.Is4() {
		return ipv4.ProtocolNumber
	}
	return ipv6.ProtocolNumber
}
