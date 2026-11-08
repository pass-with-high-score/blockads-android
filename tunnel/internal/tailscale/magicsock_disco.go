package tailscale

import (
	"crypto/rand"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"
)

func (b *MagicBind) probePeer(peer *magicPeer) {
	b.mu.RLock()
	selfDisco := b.state.DiscoKey()
	selfNodePub := b.state.NodeKey().Public
	peerDisco := peer.discoKey
	peerNode := peer.nodeKey
	candidates := append([]netip.AddrPort(nil), peer.candidates...)
	homeDERP := peer.homeDERP
	udpConn := b.udpConn
	forceDERP := b.forceDERP
	localAddrs := b.localEndpointsLocked()
	b.mu.RUnlock()

	var txID [12]byte
	_, _ = rand.Read(txID[:])
	pingPkt, err := SealDiscoPing(selfDisco, peerDisco, txID, selfNodePub)
	if err != nil {
		return
	}

	if !forceDERP && udpConn != nil {
		for _, cand := range candidates {
			_, _ = udpConn.WriteToUDPAddrPort(pingPkt, cand)
		}
	}

	if homeDERP > 0 {
		if !forceDERP {
			if cmmPkt, err := SealDiscoCallMeMaybe(selfDisco, peerDisco, localAddrs); err == nil {
				b.sendViaDERP(homeDERP, peerNode, cmmPkt)
			}
		}
		b.sendViaDERP(homeDERP, peerNode, pingPkt)
	}
	logf("[Tailscale/Disco] Sent Ping to peer %s (%d UDP candidates, DERP=%d)", peer.name, len(candidates), homeDERP)
}

func (b *MagicBind) udpReadLoop(udpConn *net.UDPConn) {
	buf := make([]byte, 65535)
	for {
		n, addrPort, err := udpConn.ReadFromUDPAddrPort(buf)
		if err != nil {
			select {
			case <-b.closed:
				return
			default:
				continue
			}
		}
		if n == 0 {
			continue
		}
		src := netip.AddrPortFrom(addrPort.Addr().Unmap(), addrPort.Port())
		pkt := append([]byte(nil), buf[:n]...)

		if IsDiscoPacket(pkt) {
			b.handleDiscoPacket(pkt, src, 0)
			continue
		}

		b.mu.RLock()
		peer := b.matchPeerByUDPLocked(src)
		b.mu.RUnlock()
		if peer != nil {
			select {
			case b.recvCh <- wgIncoming{data: pkt, ep: peer.endpoint}:
			case <-b.closed:
				return
			default:
			}
		}
	}
}

func (b *MagicBind) onDERPPacket(regionID int, srcNodeKey [32]byte, pkt []byte) {
	if IsDiscoPacket(pkt) {
		derpSrc := netip.AddrPortFrom(netip.MustParseAddr(magicEndpointIP), uint16(regionID))
		b.handleDiscoPacket(pkt, derpSrc, regionID)
		return
	}

	b.mu.RLock()
	peer := b.peersByNode[srcNodeKey]
	b.mu.RUnlock()
	if peer != nil {
		select {
		case b.recvCh <- wgIncoming{data: pkt, ep: peer.endpoint}:
		case <-b.closed:
		default:
		}
	}
}

func (b *MagicBind) handleDiscoPacket(pkt []byte, src netip.AddrPort, fromDERPRegion int) {
	b.mu.RLock()
	selfDisco := b.state.DiscoKey()
	b.mu.RUnlock()

	env, err := OpenDiscoPacket(selfDisco, pkt)
	if err != nil {
		return
	}

	b.mu.Lock()
	peer := b.peersByDisco[env.SenderDiscoPub]
	if peer == nil && env.HasNodeKey {
		peer = b.peersByNode[env.NodeKey]
		if peer != nil {
			peer.discoKey = env.SenderDiscoPub
			peer.hasDisco = true
			b.peersByDisco[env.SenderDiscoPub] = peer
		}
	}
	if peer == nil {
		b.mu.Unlock()
		return
	}

	if fromDERPRegion == 0 && src.IsValid() {
		peer.activeUDP = src
		peer.lastPong = time.Now()
		b.peersByUDP[src] = peer
	}
	peerName := peer.name
	peerNodeKey := peer.nodeKey
	peerDERP := peer.homeDERP
	if fromDERPRegion > 0 && peerDERP == 0 {
		peer.homeDERP = fromDERPRegion
		peerDERP = fromDERPRegion
	}
	udpConn := b.udpConn
	b.mu.Unlock()

	switch env.MsgType {
	case DiscoMsgPing:
		pongPkt, err := SealDiscoPong(selfDisco, env.SenderDiscoPub, env.TxID, src)
		if err != nil {
			return
		}
		if fromDERPRegion > 0 {
			logf("[Tailscale/Disco] Rx Ping from %s via DERP #%d -> replying Pong", peerName, fromDERPRegion)
			go b.sendViaDERP(fromDERPRegion, peerNodeKey, pongPkt)
		} else if udpConn != nil {
			logf("[Tailscale/Disco] Rx Ping from %s via UDP %s -> replying Pong", peerName, src)
			_, _ = udpConn.WriteToUDPAddrPort(pongPkt, src)
		}

	case DiscoMsgPong:
		b.pingWaiterMu.Lock()
		if waiter, ok := b.pingWaiters[env.TxID]; ok {
			relayName := ""
			if fromDERPRegion > 0 {
				relayName = fmt.Sprintf("DERP #%d", fromDERPRegion)
			}
			select {
			case waiter.ch <- pingProbeResult{
				rtt:    time.Since(waiter.sentAt),
				direct: fromDERPRegion == 0,
				relay:  relayName,
			}:
			default:
			}
		}
		b.pingWaiterMu.Unlock()

		if fromDERPRegion > 0 {
			logf("[Tailscale/Disco] Rx Pong from %s via DERP #%d (observed=%s)", peerName, fromDERPRegion, env.PongSrc)
		} else {
			logf("[Tailscale/Disco] Direct UDP path active with %s at %s", peerName, src)
		}

	case DiscoMsgCallMeMaybe:
		b.mu.Lock()
		for _, ap := range env.MyNumber {
			unmapped := netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())
			b.peersByUDP[unmapped] = peer
			found := false
			for _, existing := range peer.candidates {
				if existing == unmapped {
					found = true
					break
				}
			}
			if !found {
				peer.candidates = append(peer.candidates, unmapped)
			}
		}
		b.mu.Unlock()
		go b.probePeer(peer)
	}
}

func (b *MagicBind) matchPeerByUDPLocked(src netip.AddrPort) *magicPeer {
	if p, ok := b.peersByUDP[src]; ok {
		return p
	}
	for _, p := range b.peersBySlot {
		if p.activeUDP == src {
			return p
		}
		for _, c := range p.candidates {
			if c.Addr() == src.Addr() {
				return p
			}
		}
	}
	if len(b.peersBySlot) == 1 {
		for _, p := range b.peersBySlot {
			return p
		}
	}
	return nil
}

func (b *MagicBind) getOrDialDERP(regionID int, preferred bool) (*DERPClient, error) {
	b.mu.RLock()
	existing := b.derpClients[regionID]
	if existing != nil && !existing.IsClosed() {
		b.mu.RUnlock()
		return existing, nil
	}
	b.mu.RUnlock()

	b.derpDialMu.Lock()
	defer b.derpDialMu.Unlock()

	b.mu.RLock()
	existing = b.derpClients[regionID]
	if existing != nil && !existing.IsClosed() {
		b.mu.RUnlock()
		return existing, nil
	}
	derpMap := b.derpMap
	selfNodeKey := b.state.NodeKey()
	protectFn := b.protectFn
	plainHTTP := b.derpPlainHTTP
	insecure := b.derpInsecure
	b.mu.RUnlock()

	if derpMap == nil {
		logf("[Tailscale/DERP] getOrDialDERP: derpMap is nil!")
		return nil, errors.New("derpMap is nil")
	}
	reg := derpMap.Regions[regionID]
	if reg == nil || len(reg.Nodes) == 0 {
		logf("[Tailscale/DERP] getOrDialDERP: region %d not found in derpMap (total regions: %d)!", regionID, len(derpMap.Regions))
		return nil, fmt.Errorf("derp region %d not in DERPMap", regionID)
	}
	node := reg.Nodes[0]

	client, err := DialDERPNode(regionID, node, selfNodeKey, preferred, protectFn, b.onDERPPacket, insecure, plainHTTP)
	if err != nil {
		logf("[Tailscale/DERP] Failed to connect to region %d: %v", regionID, err)
		return nil, err
	}

	b.mu.Lock()
	b.derpClients[regionID] = client
	b.mu.Unlock()
	return client, nil
}

func (b *MagicBind) sendViaDERP(regionID int, dstNodeKey [32]byte, pkt []byte) {
	client, err := b.getOrDialDERP(regionID, regionID == b.HomeDERP())
	if err != nil || client == nil {
		logf("[Tailscale/DERP] sendViaDERP failed for region %d: %v", regionID, err)
		return
	}
	if err := client.SendPacket(dstNodeKey, pkt); err != nil {
		logf("[Tailscale/DERP] SendPacket failed for region %d: %v", regionID, err)
		client.Close()
	}
}

// HomeDERP returns the active home DERP region ID.
func (b *MagicBind) HomeDERP() int {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.homeDERP
}

// LocalEndpoints returns the local IP:port strings for advertising in MapRequest.Endpoints.
func (b *MagicBind) LocalEndpoints() []string {
	b.mu.RLock()
	defer b.mu.RUnlock()
	aps := b.localEndpointsLocked()
	out := make([]string, 0, len(aps))
	for _, ap := range aps {
		out = append(out, ap.String())
	}
	return out
}

func (b *MagicBind) localEndpointsLocked() []netip.AddrPort {
	if b.localPort == 0 {
		return nil
	}
	var out []netip.AddrPort
	seen := make(map[netip.AddrPort]bool)

	addCandidate := func(ip net.IP) {
		if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			return
		}
		ip4 := ip.To4()
		if ip4 == nil {
			return
		}
		if (ip4[0] == 100 && (ip4[1]&0xc0) == 64) || ip.String() == "10.0.0.2" {
			return
		}
		addr, ok := netip.AddrFromSlice(ip4)
		if !ok {
			return
		}
		ap := netip.AddrPortFrom(addr, b.localPort)
		if !seen[ap] {
			seen[ap] = true
			out = append(out, ap)
		}
	}

	if addrs, err := net.InterfaceAddrs(); err == nil {
		for _, a := range addrs {
			if ipNet, ok := a.(*net.IPNet); ok {
				addCandidate(ipNet.IP)
			}
		}
	}

	if ifaces, err := net.Interfaces(); err == nil {
		for _, ifi := range ifaces {
			if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagLoopback != 0 {
				continue
			}
			if ias, err := ifi.Addrs(); err == nil {
				for _, a := range ias {
					if ipNet, ok := a.(*net.IPNet); ok {
						addCandidate(ipNet.IP)
					}
				}
			}
		}
	}

	logf("[Tailscale/Disco] localEndpoints: localPort=%d endpoints=%v", b.localPort, out)
	return out
}

func parsePeerDERP(p *Node, fallback int) int {
	if p.HomeDERP > 0 {
		return p.HomeDERP
	}
	if strings.HasPrefix(p.DERP, "127.3.3.40:") {
		if id, err := strconv.Atoi(strings.TrimPrefix(p.DERP, "127.3.3.40:")); err == nil && id > 0 {
			return id
		}
	}
	return fallback
}
