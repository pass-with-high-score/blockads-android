package tailscale

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
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

const (
	tsNicID = 1
	tsMTU   = 1280
)

// netstackTUN bridges a gVisor channel.Endpoint directly to a WireGuard tun.Device in memory.
type netstackTUN struct {
	ep        *channel.Endpoint
	ctx       context.Context
	cancel    context.CancelFunc
	events    chan tun.Event
	closeOnce sync.Once
}

func newNetstackTUN(ep *channel.Endpoint) *netstackTUN {
	ctx, cancel := context.WithCancel(context.Background())
	events := make(chan tun.Event, 2)
	events <- tun.EventUp
	return &netstackTUN{
		ep:     ep,
		ctx:    ctx,
		cancel: cancel,
		events: events,
	}
}

func (t *netstackTUN) File() *os.File { return nil }

func (t *netstackTUN) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	pkt := t.ep.ReadContext(t.ctx)
	if pkt == nil {
		return 0, os.ErrClosed
	}
	v := pkt.ToView()
	n := copy(bufs[0][offset:], v.AsSlice())
	v.Release()
	pkt.DecRef()
	sizes[0] = n
	return 1, nil
}

func (t *netstackTUN) Write(bufs [][]byte, offset int) (int, error) {
	for _, buf := range bufs {
		raw := buf[offset:]
		if len(raw) == 0 {
			continue
		}
		var proto tcpip.NetworkProtocolNumber
		switch raw[0] >> 4 {
		case 4:
			proto = ipv4.ProtocolNumber
		case 6:
			proto = ipv6.ProtocolNumber
		default:
			continue
		}
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(append([]byte(nil), raw...)),
		})
		t.ep.InjectInbound(proto, pkt)
		pkt.DecRef()
	}
	return len(bufs), nil
}

func (t *netstackTUN) MTU() (int, error)        { return tsMTU, nil }
func (t *netstackTUN) Name() (string, error)    { return "ts-tun0", nil }
func (t *netstackTUN) Events() <-chan tun.Event { return t.events }
func (t *netstackTUN) BatchSize() int           { return 1 }

func (t *netstackTUN) Close() error {
	t.closeOnce.Do(func() {
		t.cancel()
		close(t.events)
	})
	return nil
}

// DataPlane manages the userspace WireGuard device, MagicBind, and gVisor TCP/IP netstack for Tailscale.
type DataPlane struct {
	mu           sync.Mutex
	state        *State
	bind         *MagicBind
	tunDev       *netstackTUN
	wgDev        *device.Device
	netStack     *stack.Stack
	chanEP       *channel.Endpoint
	assignedAddrs map[netip.Addr]bool
	running      bool
}

// NewDataPlane initializes a new Tailscale DataPlane.
func NewDataPlane(state *State, protectFn func(fd int) bool) (*DataPlane, error) {
	if state == nil {
		return nil, errors.New("dataplane: nil state")
	}

	s := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
			icmp.NewProtocol4,
			icmp.NewProtocol6,
		},
	})

	ep := channel.New(1024, tsMTU, "")
	if err := s.CreateNIC(tsNicID, ep); err != nil {
		s.Close()
		return nil, fmt.Errorf("create tailscale NIC: %s", err)
	}

	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: tsNicID},
		{Destination: header.IPv6EmptySubnet, NIC: tsNicID},
	})

	tunDev := newNetstackTUN(ep)
	bind := NewMagicBind(state, protectFn)
	logger := device.NewLogger(device.LogLevelError, "[Tailscale/WG] ")
	wgDev := device.NewDevice(tunDev, bind, logger)

	var uapi strings.Builder
	fmt.Fprintf(&uapi, "private_key=%s\n", state.NodeKey().PrivateHex())
	if err := wgDev.IpcSet(uapi.String()); err != nil {
		wgDev.Close()
		s.Close()
		return nil, fmt.Errorf("set wg private key: %w", err)
	}
	if err := wgDev.Up(); err != nil {
		wgDev.Close()
		s.Close()
		return nil, fmt.Errorf("bring up tailscale wg device: %w", err)
	}

	return &DataPlane{
		state:         state,
		bind:          bind,
		tunDev:        tunDev,
		wgDev:         wgDev,
		netStack:      s,
		chanEP:        ep,
		assignedAddrs: make(map[netip.Addr]bool),
		running:       true,
	}, nil
}

// Bind returns the underlying MagicBind.
func (dp *DataPlane) Bind() *MagicBind {
	return dp.bind
}

// SetProtectFn updates the socket protection callback on the underlying MagicBind.
func (dp *DataPlane) SetProtectFn(fn func(fd int) bool) {
	if dp.bind != nil {
		dp.bind.SetProtectFn(fn)
	}
}

// Sync updates self addresses, peer WireGuard keys/AllowedIPs, and DERP map.
func (dp *DataPlane) Sync(selfNode *Node, peers map[int64]*Node, derpMap *DERPMap, activeExitID int64) error {
	dp.mu.Lock()
	defer dp.mu.Unlock()

	if !dp.running {
		return errors.New("dataplane stopped")
	}

	if selfNode != nil {
		for _, cidrStr := range selfNode.Addresses {
			prefix, err := netip.ParsePrefix(cidrStr)
			if err != nil {
				if addr, aerr := netip.ParseAddr(cidrStr); aerr == nil {
					prefix = netip.PrefixFrom(addr, addr.BitLen())
				} else {
					continue
				}
			}
			addr := prefix.Addr().Unmap()
			if dp.assignedAddrs[addr] {
				continue
			}
			pa := tcpip.ProtocolAddress{
				Protocol:          tsProtoOf(addr),
				AddressWithPrefix: tcpip.AddrFromSlice(addr.AsSlice()).WithPrefix(),
			}
			if err := dp.netStack.AddProtocolAddress(tsNicID, pa, stack.AddressProperties{}); err == nil {
				dp.assignedAddrs[addr] = true
				logf("[Tailscale/WG] Assigned local Tailnet IP %s to netstack", addr)
			}
		}
	}

	dp.bind.UpsertPeers(peers, derpMap)

	var uapi strings.Builder
	fmt.Fprintf(&uapi, "private_key=%s\n", dp.state.NodeKey().PrivateHex())
	fmt.Fprintf(&uapi, "replace_peers=true\n")

	peerCount := 0
	for _, p := range peers {
		peerKey, err := ParseKeyHex(p.Key, "nodekey:")
		if err != nil {
			continue
		}
		epStr, ok := dp.bind.PeerSlotEndpoint(peerKey)
		if !ok {
			continue
		}

		fmt.Fprintf(&uapi, "public_key=%s\n", hex.EncodeToString(peerKey[:]))
		fmt.Fprintf(&uapi, "endpoint=%s\n", epStr)
		fmt.Fprintf(&uapi, "replace_allowed_ips=true\n")
		if activeExitID != 0 && p.ID == activeExitID {
			fmt.Fprintf(&uapi, "persistent_keepalive_interval=25\n")
		}

		seenCIDR := make(map[string]bool)
		addCIDR := func(raw string) {
			raw = strings.TrimSpace(raw)
			if raw == "" {
				return
			}
			if !strings.Contains(raw, "/") {
				if ip := net.ParseIP(raw); ip != nil {
					if ip.To4() != nil {
						raw += "/32"
					} else {
						raw += "/128"
					}
				}
			}
			if _, _, err := net.ParseCIDR(raw); err == nil && !seenCIDR[raw] {
				seenCIDR[raw] = true
				fmt.Fprintf(&uapi, "allowed_ip=%s\n", raw)
			}
		}

		for _, a := range p.AllowedIPs {
			trimmed := strings.TrimSpace(a)
			if trimmed == "0.0.0.0/0" || trimmed == "::/0" {
				continue
			}
			addCIDR(trimmed)
		}
		for _, a := range p.Addresses {
			addCIDR(a)
		}
		if activeExitID != 0 && p.ID == activeExitID {
			addCIDR("0.0.0.0/0")
			addCIDR("::/0")
		}
		peerCount++
	}

	if err := dp.wgDev.IpcSet(uapi.String()); err != nil {
		logf("[Tailscale/WG] IpcSet failed: %v", err)
		return err
	}
	logf("[Tailscale/WG] Synced %d peers into WireGuard engine (exitNode=%d)", peerCount, activeExitID)
	return nil
}

// DialTCP opens a TCP connection to dstIP:dstPort over the Tailscale WireGuard netstack.
func (dp *DataPlane) DialTCP(ctx context.Context, dstIP net.IP, dstPort int, peer *Node) (net.Conn, error) {
	dp.mu.Lock()
	running := dp.running
	s := dp.netStack
	dp.mu.Unlock()

	if !running || s == nil {
		return nil, errors.New("tailscale dataplane not running")
	}

	addr, ok := netip.AddrFromSlice(dstIP)
	if !ok {
		return nil, fmt.Errorf("invalid destination IP: %v", dstIP)
	}
	addr = addr.Unmap()

	peerName := "unknown"
	if peer != nil {
		peerName = strings.TrimSuffix(peer.Name, ".")
		if pk, err := ParseKeyHex(peer.Key, "nodekey:"); err == nil {
			dp.bind.ProbePeerByKey(pk)
		}
	}

	logf("[Tailscale/Route] Dialing TCP %s:%d via Tailscale peer %s", addr, dstPort, peerName)
	fa := tcpip.FullAddress{
		NIC:  tsNicID,
		Addr: tcpip.AddrFromSlice(addr.AsSlice()),
		Port: uint16(dstPort),
	}
	conn, err := gonet.DialContextTCP(ctx, s, fa, tsProtoOf(addr))
	if err != nil {
		logf("[Tailscale/WG] TCP dial to %s:%d (peer=%s) failed: %v", addr, dstPort, peerName, err)
		return nil, err
	}
	logf("[Tailscale/WG] TCP connected to %s:%d (peer=%s)", addr, dstPort, peerName)
	return conn, nil
}

// DialUDP opens a connected UDP socket to dstIP:dstPort over the Tailscale WireGuard netstack.
func (dp *DataPlane) DialUDP(dstIP net.IP, dstPort int, peer *Node) (net.Conn, error) {
	dp.mu.Lock()
	running := dp.running
	s := dp.netStack
	dp.mu.Unlock()

	if !running || s == nil {
		return nil, errors.New("tailscale dataplane not running")
	}

	addr, ok := netip.AddrFromSlice(dstIP)
	if !ok {
		return nil, fmt.Errorf("invalid destination IP: %v", dstIP)
	}
	addr = addr.Unmap()

	if peer != nil {
		if pk, err := ParseKeyHex(peer.Key, "nodekey:"); err == nil {
			dp.bind.ProbePeerByKey(pk)
		}
	}

	fa := tcpip.FullAddress{
		NIC:  tsNicID,
		Addr: tcpip.AddrFromSlice(addr.AsSlice()),
		Port: uint16(dstPort),
	}
	return gonet.DialUDP(s, nil, &fa, tsProtoOf(addr))
}

// ListenTCP listens for incoming TCP connections on the local Tailscale netstack.
// If localIP is nil or empty, it binds to all addresses on the Tailscale NIC.
func (dp *DataPlane) ListenTCP(localIP net.IP, port int) (net.Listener, error) {
	dp.mu.Lock()
	running := dp.running
	s := dp.netStack
	dp.mu.Unlock()

	if !running || s == nil {
		return nil, errors.New("tailscale dataplane not running")
	}

	if len(localIP) == 0 {
		fa := tcpip.FullAddress{
			NIC:  tsNicID,
			Port: uint16(port),
		}
		return gonet.ListenTCP(s, fa, ipv4.ProtocolNumber)
	}

	addr, ok := netip.AddrFromSlice(localIP)
	if !ok {
		return nil, fmt.Errorf("invalid local IP: %v", localIP)
	}
	addr = addr.Unmap()
	fa := tcpip.FullAddress{
		NIC:  tsNicID,
		Addr: tcpip.AddrFromSlice(addr.AsSlice()),
		Port: uint16(port),
	}
	return gonet.ListenTCP(s, fa, tsProtoOf(addr))
}

// Close shuts down the WireGuard device, MagicBind, and gVisor stack.
func (dp *DataPlane) Close() {
	dp.mu.Lock()
	if !dp.running {
		dp.mu.Unlock()
		return
	}
	dp.running = false
	wgDev := dp.wgDev
	tunDev := dp.tunDev
	bind := dp.bind
	s := dp.netStack
	ep := dp.chanEP
	dp.mu.Unlock()

	if wgDev != nil {
		wgDev.Close()
	}
	if tunDev != nil {
		_ = tunDev.Close()
	}
	if bind != nil {
		bind.Shutdown()
	}
	if s != nil {
		s.Close()
	}
	if ep != nil {
		ep.Close()
	}
}

func tsProtoOf(a netip.Addr) tcpip.NetworkProtocolNumber {
	if a.Is4() {
		return ipv4.ProtocolNumber
	}
	return ipv6.ProtocolNumber
}
