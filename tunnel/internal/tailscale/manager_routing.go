package tailscale

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"time"
)

// ResolveMagicDNS resolves a hostname (e.g. "nas" or "nas.tailxxxx.ts.net") from the MagicDNS table.
func (m *Manager) ResolveMagicDNS(name string) net.IP {
	m.mu.RLock()
	defer m.mu.RUnlock()
	clean := strings.ToLower(strings.TrimSuffix(name, "."))
	if ip := m.dnsRecords[clean]; ip != nil {
		logf("[Tailscale/DNS] MagicDNS resolved %s -> %s", clean, ip)
		return ip
	}
	return nil
}

// MatchPeer checks if an IP belongs to any known Tailscale peer using Longest Prefix Match.
func (m *Manager) MatchPeer(ip net.IP) *Node {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.matchPeerLocked(ip)
}

func (m *Manager) matchPeerLocked(ip net.IP) *Node {
	if ip == nil {
		return nil
	}
	var bestPeer *Node
	bestMaskLen := -1

	checkCIDR := func(p *Node, raw string) {
		raw = strings.TrimSpace(raw)
		if (raw == "0.0.0.0/0" || raw == "::/0") && (m.activeExitID == 0 || p.ID != m.activeExitID) {
			return
		}
		if !strings.Contains(raw, "/") {
			if parsed := net.ParseIP(raw); parsed != nil {
				if parsed.Equal(ip) && 32 > bestMaskLen {
					bestMaskLen = 32
					bestPeer = p
				}
			}
			return
		}
		_, cidr, err := net.ParseCIDR(raw)
		if err == nil && cidr.Contains(ip) {
			ones, _ := cidr.Mask.Size()
			if ones > bestMaskLen {
				bestMaskLen = ones
				bestPeer = p
			}
		}
	}

	for _, p := range m.peersByID {
		for _, allowed := range p.AllowedIPs {
			checkCIDR(p, allowed)
		}
		for _, addr := range p.Addresses {
			checkCIDR(p, addr)
		}
		if m.activeExitID != 0 && p.ID == m.activeExitID && bestPeer == nil {
			bestPeer = p
			bestMaskLen = 0
		}
	}
	return bestPeer
}

// IsTailscaleCGNAT reports whether ip is inside the Tailscale CGNAT range (100.64.0.0/10)
// and is not one of BlockAds' internal synthetic TUN/DNS addresses (100.64.100.1, 100.64.100.2).
func IsTailscaleCGNAT(ip net.IP) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	if ip4[0] == 100 && ip4[1] >= 64 && ip4[1] <= 127 {
		if ip4[1] == 64 && ip4[2] == 100 && (ip4[3] == 1 || ip4[3] == 2) {
			return false
		}
		return true
	}
	return false
}

// DialTCP routes a TCP connection through the Tailscale WireGuard DataPlane if the destination IP
// matches a Tailscale peer, an active Exit Node, or falls within the Tailscale CGNAT range.
func (m *Manager) DialTCP(ctx context.Context, dstIP net.IP, dstPort int) (net.Conn, bool, error) {
	m.mu.RLock()
	peer := m.matchPeerLocked(dstIP)
	dp := m.dataPlane
	peerCount := len(m.peersByID)
	m.mu.RUnlock()

	if peer == nil {
		if IsTailscaleCGNAT(dstIP) {
			logf("[Tailscale/Route] Blocked leak to Tailscale IP %s:%d (no matching peer in map, totalPeers=%d)", dstIP, dstPort, peerCount)
			return nil, true, fmt.Errorf("tailscale peer not found for %s (total peers: %d)", dstIP, peerCount)
		}
		return nil, false, nil
	}

	if dp == nil {
		return nil, true, fmt.Errorf("tailscale dataplane not initialized")
	}

	conn, err := dp.DialTCP(ctx, dstIP, dstPort, peer)
	return conn, true, err
}

// DialUDP routes a UDP flow through the Tailscale WireGuard DataPlane if the destination IP
// matches a Tailscale peer, an active Exit Node, or falls within the Tailscale CGNAT range.
func (m *Manager) DialUDP(dstIP net.IP, dstPort int) (net.Conn, bool, error) {
	m.mu.RLock()
	peer := m.matchPeerLocked(dstIP)
	dp := m.dataPlane
	peerCount := len(m.peersByID)
	m.mu.RUnlock()

	if peer == nil {
		if IsTailscaleCGNAT(dstIP) {
			return nil, true, fmt.Errorf("tailscale peer not found for UDP %s (total peers: %d)", dstIP, peerCount)
		}
		return nil, false, nil
	}

	if dp == nil {
		return nil, true, fmt.Errorf("tailscale dataplane not initialized")
	}

	conn, err := dp.DialUDP(dstIP, dstPort, peer)
	return conn, true, err
}

// SetExitNode selects an exit node by its ID (0 to disable).
func (m *Manager) SetExitNode(id int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.activeExitID = id
	m.status.ActiveExitID = id
	if m.dataPlane != nil {
		_ = m.dataPlane.Sync(m.status.SelfNode, m.peersByID, m.derpMap, m.activeExitID)
	}
}

func (m *Manager) maybeAdvertiseEndpointsLocked(endpoints []string, homeDERP int) {
	if homeDERP <= 0 {
		return
	}
	if homeDERP == m.lastAdvertisedDERP && stringSlicesEqual(endpoints, m.lastAdvertisedEndpoints) {
		return
	}
	m.lastAdvertisedDERP = homeDERP
	m.lastAdvertisedEndpoints = append([]string(nil), endpoints...)
	hostname := m.hostname
	client := m.client
	if client == nil {
		return
	}
	logf("[Tailscale] Triggering endpoint update (endpoints=%v, homeDERP=%d)", endpoints, homeDERP)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := client.UpdateEndpoints(ctx, hostname, endpoints, homeDERP); err != nil {
			logf("[Tailscale] update endpoints failed: %v", err)
		}
	}()
}

func stringSlicesEqual(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// PingPeer measures latency to a peer by node ID or IP address.
func (m *Manager) PingPeer(target string) (int64, bool, string, error) {
	m.mu.RLock()
	dp := m.dataPlane
	var targetPeer *Node

	cleanTarget := strings.TrimSpace(target)
	if idx := strings.Index(cleanTarget, "/"); idx != -1 {
		cleanTarget = cleanTarget[:idx]
	}

	if id, err := strconv.ParseInt(cleanTarget, 10, 64); err == nil {
		targetPeer = m.peersByID[id]
	}
	if targetPeer == nil {
		if ip := net.ParseIP(cleanTarget); ip != nil {
			targetPeer = m.matchPeerLocked(ip)
		}
	}
	selfNode := m.status.SelfNode
	m.mu.RUnlock()

	if targetPeer == nil {
		return 0, false, "", fmt.Errorf("peer %s not found", target)
	}
	if selfNode != nil && targetPeer.ID == selfNode.ID {
		return 0, false, "", errors.New("cannot ping self device")
	}
	if targetPeer.Online != nil && !*targetPeer.Online {
		return 0, false, "", errors.New("peer is offline")
	}
	if dp == nil || dp.Bind() == nil {
		return 0, false, "", errors.New("dataplane not ready")
	}

	return dp.Bind().ProbePing(targetPeer.ID, 3*time.Second)
}

// GetStateJSON returns the serialized cryptographic state.
func (m *Manager) GetStateJSON() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.state == nil {
		return "{}"
	}
	return m.state.ToJSON()
}

// GetStatusJSON returns the current status as a JSON string for the UI.
func (m *Manager) GetStatusJSON() string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	b, err := json.Marshal(m.status)
	if err != nil {
		return "{}"
	}
	return string(b)
}

// Stop terminates the control session and disconnects.
func (m *Manager) Stop() {
	m.mu.Lock()
	if m.cancelMap != nil {
		m.cancelMap()
		m.cancelMap = nil
	}
	if m.client != nil {
		m.client.Close()
		m.client = nil
	}
	m.stopPeerAPILocked()
	dp := m.dataPlane
	m.dataPlane = nil
	m.status.State = "DISCONNECTED"
	m.status.AuthURL = ""
	m.mu.Unlock()

	if dp != nil {
		dp.Close()
	}
}
