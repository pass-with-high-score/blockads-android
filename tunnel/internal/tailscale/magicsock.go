package tailscale

import (
	"crypto/rand"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
)

const (
	magicEndpointIP    = "127.3.3.40"
	discoProbeInterval = 2 * time.Second
	directUDPTimeout   = 45 * time.Second
)

// MagicEndpoint implements conn.Endpoint for a Tailscale peer identified by a slot port on 127.3.3.40.
type MagicEndpoint struct {
	addrPort netip.AddrPort
	slot     uint16
}

func (e *MagicEndpoint) ClearSrc()           {}
func (e *MagicEndpoint) SrcToString() string { return "" }
func (e *MagicEndpoint) DstToString() string { return e.addrPort.String() }
func (e *MagicEndpoint) DstToBytes() []byte {
	b, _ := e.addrPort.MarshalBinary()
	return b
}
func (e *MagicEndpoint) DstIP() netip.Addr { return e.addrPort.Addr() }
func (e *MagicEndpoint) SrcIP() netip.Addr { return netip.Addr{} }

type magicPeer struct {
	nodeID     int64
	name       string
	nodeKey    [32]byte
	discoKey   [32]byte
	hasDisco   bool
	homeDERP   int
	slot       uint16
	endpoint   *MagicEndpoint
	candidates []netip.AddrPort
	activeUDP  netip.AddrPort
	lastPong   time.Time
	lastPing   time.Time
}

type wgIncoming struct {
	data []byte
	ep   conn.Endpoint
}

// MagicBind implements conn.Bind for Tailscale, multiplexing direct UDP, Disco NAT traversal, and DERP relays.
type MagicBind struct {
	mu        sync.RWMutex
	state     *State
	protectFn func(fd int) bool
	udpConn   *net.UDPConn
	localPort uint16

	peersBySlot  map[uint16]*magicPeer
	peersByNode  map[[32]byte]*magicPeer
	peersByDisco map[[32]byte]*magicPeer
	peersByUDP   map[netip.AddrPort]*magicPeer
	nextSlot     uint16

	derpMap       *DERPMap
	homeDERP      int
	derpDialMu    sync.Mutex
	derpClients   map[int]*DERPClient
	derpPlainHTTP bool
	derpInsecure  bool
	forceDERP     bool

	recvCh chan wgIncoming
	closed chan struct{}
	isOpen bool

	pingWaiterMu sync.Mutex
	pingWaiters  map[[12]byte]pingWaiter
}

type pingWaiter struct {
	sentAt time.Time
	ch     chan pingProbeResult
}

type pingProbeResult struct {
	rtt    time.Duration
	direct bool
	relay  string
}

// NewMagicBind creates a new MagicBind instance.
func NewMagicBind(state *State, protectFn func(fd int) bool) *MagicBind {
	return &MagicBind{
		state:        state,
		protectFn:    protectFn,
		peersBySlot:  make(map[uint16]*magicPeer),
		peersByNode:  make(map[[32]byte]*magicPeer),
		peersByDisco: make(map[[32]byte]*magicPeer),
		peersByUDP:   make(map[netip.AddrPort]*magicPeer),
		derpClients:  make(map[int]*DERPClient),
		recvCh:       make(chan wgIncoming, 512),
		closed:       make(chan struct{}),
		pingWaiters:  make(map[[12]byte]pingWaiter),
	}
}

// SetDERPTestOptions configures plain HTTP / insecure TLS for local test DERP servers.
func (b *MagicBind) SetDERPTestOptions(plainHTTP, insecure bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.derpPlainHTTP = plainHTTP
	b.derpInsecure = insecure
}

// SetForceDERP disables direct UDP sends/receives to test pure DERP relay fallback.
func (b *MagicBind) SetForceDERP(force bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.forceDERP = force
}

// SetProtectFn updates the Android VPN socket protection callback and applies it to the active UDP socket.
func (b *MagicBind) SetProtectFn(fn func(fd int) bool) {
	b.mu.Lock()
	b.protectFn = fn
	udpConn := b.udpConn
	b.mu.Unlock()

	if fn != nil && udpConn != nil {
		if raw, err := udpConn.SyscallConn(); err == nil {
			_ = raw.Control(func(fd uintptr) {
				fn(int(fd))
			})
		}
	}
}

// Open implements conn.Bind.Open.
func (b *MagicBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.isOpen && b.udpConn != nil {
		return []conn.ReceiveFunc{b.receiveWG}, b.localPort, nil
	}

	b.closed = make(chan struct{})
	b.recvCh = make(chan wgIncoming, 512)

	udpConn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: int(port)})
	if err != nil {
		return nil, 0, err
	}
	if b.protectFn != nil {
		if raw, err := udpConn.SyscallConn(); err == nil {
			_ = raw.Control(func(fd uintptr) {
				b.protectFn(int(fd))
			})
		}
	}

	b.udpConn = udpConn
	b.localPort = uint16(udpConn.LocalAddr().(*net.UDPAddr).Port)
	b.isOpen = true

	go b.udpReadLoop(udpConn)
	logf("[Tailscale/Disco] MagicBind listening on UDP port %d", b.localPort)

	return []conn.ReceiveFunc{b.receiveWG}, b.localPort, nil
}

// Close implements conn.Bind.Close.
func (b *MagicBind) Close() error {
	b.mu.Lock()
	if !b.isOpen {
		b.mu.Unlock()
		return nil
	}
	b.isOpen = false
	close(b.closed)
	udpConn := b.udpConn
	b.udpConn = nil
	clients := make([]*DERPClient, 0, len(b.derpClients))
	for k, c := range b.derpClients {
		clients = append(clients, c)
		delete(b.derpClients, k)
	}
	b.mu.Unlock()

	if udpConn != nil {
		_ = udpConn.Close()
	}
	for _, c := range clients {
		c.Close()
	}
	return nil
}

// Shutdown permanently closes the MagicBind and all underlying sockets.
func (b *MagicBind) Shutdown() {
	_ = b.Close()
}

// SetMark implements conn.Bind.SetMark.
func (b *MagicBind) SetMark(mark uint32) error { return nil }

// BatchSize implements conn.Bind.BatchSize.
func (b *MagicBind) BatchSize() int { return 1 }

// ParseEndpoint implements conn.Bind.ParseEndpoint.
func (b *MagicBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	ap, err := netip.ParseAddrPort(s)
	if err != nil {
		return nil, err
	}
	return &MagicEndpoint{
		addrPort: ap,
		slot:     ap.Port(),
	}, nil
}

func (b *MagicBind) receiveWG(packets [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
	select {
	case <-b.closed:
		return 0, net.ErrClosed
	case pkt, ok := <-b.recvCh:
		if !ok {
			return 0, net.ErrClosed
		}
		n := copy(packets[0], pkt.data)
		sizes[0] = n
		eps[0] = pkt.ep
		return 1, nil
	}
}

// Send implements conn.Bind.Send.
func (b *MagicBind) Send(bufs [][]byte, ep conn.Endpoint) error {
	mep, ok := ep.(*MagicEndpoint)
	if !ok {
		return errors.New("magicbind: invalid endpoint type")
	}

	b.mu.RLock()
	peer := b.peersBySlot[mep.slot]
	udpConn := b.udpConn
	b.mu.RUnlock()

	if peer == nil {
		return errors.New("magicbind: unknown peer slot")
	}

	now := time.Now()
	b.mu.Lock()
	forceDERP := b.forceDERP
	activeUDP := peer.activeUDP
	directValid := !forceDERP && activeUDP.IsValid() && (!peer.hasDisco || now.Sub(peer.lastPong) < directUDPTimeout)
	needProbe := peer.hasDisco && (!directValid || now.Sub(peer.lastPong) > 15*time.Second) && now.Sub(peer.lastPing) >= discoProbeInterval
	if needProbe {
		peer.lastPing = now
	}
	candidates := append([]netip.AddrPort(nil), peer.candidates...)
	homeDERP := peer.homeDERP
	nodeKey := peer.nodeKey
	b.mu.Unlock()

	if needProbe {
		go b.probePeer(peer)
	}

	for _, pkt := range bufs {
		if len(pkt) == 0 {
			continue
		}
		if directValid && udpConn != nil {
			_, _ = udpConn.WriteToUDPAddrPort(pkt, activeUDP)
			continue
		}
		if !forceDERP && udpConn != nil {
			for _, cand := range candidates {
				_, _ = udpConn.WriteToUDPAddrPort(pkt, cand)
			}
		}
		if homeDERP > 0 {
			dataCopy := append([]byte(nil), pkt...)
			go b.sendViaDERP(homeDERP, nodeKey, dataCopy)
		}
	}
	return nil
}

// UpsertPeers updates the peer routing table and DERP map from a Control MapResponse.
func (b *MagicBind) UpsertPeers(peers map[int64]*Node, derpMap *DERPMap) {
	b.mu.Lock()
	if derpMap != nil && len(derpMap.Regions) > 0 {
		b.derpMap = derpMap
		if b.homeDERP == 0 {
			for _, p := range peers {
				if pDERP := parsePeerDERP(p, 0); pDERP > 0 && derpMap.Regions[pDERP] != nil {
					b.homeDERP = pDERP
					break
				}
			}
			if b.homeDERP == 0 && derpMap.Regions[3] != nil {
				b.homeDERP = 3
			}
			if b.homeDERP == 0 {
				for regionID, reg := range derpMap.Regions {
					if reg != nil && len(reg.Nodes) > 0 && (b.homeDERP == 0 || regionID < b.homeDERP) {
						b.homeDERP = regionID
					}
				}
			}
			logf("[Tailscale/DERP] Selected Home DERP region %d", b.homeDERP)
		}
	}

	for _, p := range peers {
		nodeKey, err := ParseKeyHex(p.Key, "nodekey:")
		if err != nil {
			continue
		}
		mp, exists := b.peersByNode[nodeKey]
		if !exists {
			b.nextSlot++
			if b.nextSlot == 0 {
				b.nextSlot = 1
			}
			slot := b.nextSlot
			ap := netip.MustParseAddrPort(fmt.Sprintf("%s:%d", magicEndpointIP, slot))
			mp = &magicPeer{
				slot:     slot,
				nodeKey:  nodeKey,
				endpoint: &MagicEndpoint{addrPort: ap, slot: slot},
			}
			b.peersBySlot[slot] = mp
			b.peersByNode[nodeKey] = mp
		}
		mp.nodeID = p.ID
		mp.name = strings.TrimSuffix(p.Name, ".")
		if dk, err := ParseKeyHex(p.DiscoKey, "discokey:"); err == nil && dk != [32]byte{} {
			mp.discoKey = dk
			mp.hasDisco = true
			b.peersByDisco[dk] = mp
		}
		mp.homeDERP = parsePeerDERP(p, b.homeDERP)

		var cands []netip.AddrPort
		rawEndpoints := append([]string(nil), p.Endpoints...)
		rawEndpoints = append(rawEndpoints, p.Addrs...)
		for _, epStr := range rawEndpoints {
			if ap, err := netip.ParseAddrPort(epStr); err == nil {
				unmapped := netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())
				cands = append(cands, unmapped)
				b.peersByUDP[unmapped] = mp
			}
		}
		mp.candidates = cands
		if !mp.hasDisco && len(cands) > 0 && !mp.activeUDP.IsValid() {
			mp.activeUDP = cands[0]
		}
	}
	homeRegion := b.homeDERP
	b.mu.Unlock()

	if homeRegion > 0 {
		go func() { _, _ = b.getOrDialDERP(homeRegion, true) }()
	}
}

// PeerSlotEndpoint returns the virtual WireGuard endpoint string ("127.3.3.40:<slot>") for a peer's NodeKey.
func (b *MagicBind) PeerSlotEndpoint(nodeKey [32]byte) (string, bool) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	mp, ok := b.peersByNode[nodeKey]
	if !ok {
		return "", false
	}
	return mp.endpoint.DstToString(), true
}

// ProbePeerByKey triggers an immediate Disco ping over UDP + DERP for the given peer.
func (b *MagicBind) ProbePeerByKey(nodeKey [32]byte) {
	b.mu.RLock()
	mp := b.peersByNode[nodeKey]
	b.mu.RUnlock()
	if mp != nil && mp.hasDisco {
		go b.probePeer(mp)
	}
}

// PeerRouteInfo returns current active UDP address and DERP region for a peer.
func (b *MagicBind) PeerRouteInfo(nodeID int64) (curAddr string, homeDERP int) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	for _, mp := range b.peersByNode {
		if mp.nodeID == nodeID {
			if mp.activeUDP.IsValid() {
				curAddr = mp.activeUDP.String()
			}
			homeDERP = mp.homeDERP
			return
		}
	}
	return
}

// ProbePing sends an on-demand Disco ping probe to measure latency to the peer.
func (b *MagicBind) ProbePing(nodeID int64, timeout time.Duration) (int64, bool, string, error) {
	b.mu.RLock()
	var target *magicPeer
	for _, mp := range b.peersByNode {
		if mp.nodeID == nodeID {
			target = mp
			break
		}
	}
	b.mu.RUnlock()

	if target == nil {
		return 0, false, "", errors.New("peer not found in magicsock")
	}

	b.mu.RLock()
	selfDisco := b.state.DiscoKey()
	selfNodePub := b.state.NodeKey().Public
	peerDisco := target.discoKey
	peerNode := target.nodeKey
	candidates := append([]netip.AddrPort(nil), target.candidates...)
	homeDERP := target.homeDERP
	udpConn := b.udpConn
	activeUDP := target.activeUDP
	b.mu.RUnlock()

	var txID [12]byte
	if _, err := rand.Read(txID[:]); err != nil {
		return 0, false, "", err
	}

	pingPkt, err := SealDiscoPing(selfDisco, peerDisco, txID, selfNodePub)
	if err != nil {
		return 0, false, "", err
	}

	ch := make(chan pingProbeResult, 1)
	b.pingWaiterMu.Lock()
	b.pingWaiters[txID] = pingWaiter{sentAt: time.Now(), ch: ch}
	b.pingWaiterMu.Unlock()

	defer func() {
		b.pingWaiterMu.Lock()
		delete(b.pingWaiters, txID)
		b.pingWaiterMu.Unlock()
	}()

	if udpConn != nil {
		if activeUDP.IsValid() {
			_, _ = udpConn.WriteToUDPAddrPort(pingPkt, activeUDP)
		}
		for _, cand := range candidates {
			if cand != activeUDP {
				_, _ = udpConn.WriteToUDPAddrPort(pingPkt, cand)
			}
		}
	}
	if homeDERP > 0 {
		b.sendViaDERP(homeDERP, peerNode, pingPkt)
	}

	select {
	case res := <-ch:
		return res.rtt.Milliseconds(), res.direct, res.relay, nil
	case <-time.After(timeout):
		if activeUDP.IsValid() {
			return 0, true, "", errors.New("ping timeout")
		}
		return 0, false, "", errors.New("ping timeout")
	}
}
