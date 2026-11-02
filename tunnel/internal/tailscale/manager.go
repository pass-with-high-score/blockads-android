package tailscale

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"time"
)

func logf(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, "[BlockAds/Go] "+format+"\n", args...)
}

// Status represents the current state of the Tailscale connection.
type Status struct {
	State         string      `json:"state"` // "DISCONNECTED", "AUTHENTICATING", "NEEDS_AUTH", "CONNECTED", "ERROR"
	AuthURL       string      `json:"auth_url,omitempty"`
	Error         string      `json:"error,omitempty"`
	SelfNode      *Node       `json:"self_node,omitempty"`
	TailscaleIPs  []string    `json:"tailscale_ips,omitempty"`
	MagicDNSName  string      `json:"magic_dns_name,omitempty"`
	Peers         []PeerInfo  `json:"peers,omitempty"`
	ExitNodes     []ExitNode  `json:"exit_nodes,omitempty"`
	ActiveExitID  int64       `json:"active_exit_id,omitempty"`
}

// PeerInfo is a summary of a peer device for the UI.
type PeerInfo struct {
	ID        int64    `json:"id"`
	Name      string   `json:"name"`
	IPs       []string `json:"ips"`
	OS        string   `json:"os,omitempty"`
	Online    bool     `json:"online"`
	LastSeen  int64    `json:"last_seen,omitempty"`
	IsExit    bool     `json:"is_exit_node"`
}

// ExitNode represents a peer capable of acting as an Internet exit node.
type ExitNode struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
	IP   string `json:"ip"`
}

// Manager coordinates the Tailscale identity, control session, peer list, and MagicDNS table.
type Manager struct {
	mu           sync.RWMutex
	state        *State
	protectFn    func(fd int) bool
	client       *ControlClient
	cancelMap    context.CancelFunc
	status       Status

	peersByID    map[int64]*Node
	dnsRecords   map[string]net.IP // lowercase fqdn -> IP
	activeExitID int64
}

// NewManager creates a Tailscale Manager.
func NewManager(state *State, protectFn func(fd int) bool) *Manager {
	return &Manager{
		state:      state,
		protectFn:  protectFn,
		peersByID:  make(map[int64]*Node),
		dnsRecords: make(map[string]net.IP),
		status: Status{
			State: "DISCONNECTED",
		},
	}
}

// StartAuth begins registration. If interactive login is required, AuthURL is returned.
func (m *Manager) StartAuth(authKey, controlURL, hostname string) (string, error) {
	m.mu.Lock()
	if controlURL != "" {
		m.state.ControlURL = controlURL
	}
	if authKey != "" {
		m.state.AuthKey = authKey
	}
	m.status.State = "AUTHENTICATING"
	m.status.Error = ""
	m.status.AuthURL = ""
	m.client = NewControlClient(m.state, m.protectFn)
	client := m.client
	m.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	authURL, authorized, err := client.Register(ctx, hostname, "")
	m.mu.Lock()
	defer m.mu.Unlock()

	if err != nil {
		m.status.State = "ERROR"
		m.status.Error = err.Error()
		return "", err
	}

	if authorized {
		m.status.State = "CONNECTED"
		m.status.AuthURL = ""
		go func() { _ = m.StartSession(hostname) }()
		return "", nil
	}

	if authURL != "" {
		m.status.State = "NEEDS_AUTH"
		m.status.AuthURL = authURL
		go m.followupAuth(authURL, hostname)
		return authURL, nil
	}

	m.status.State = "CONNECTED"
	return "", nil
}

func (m *Manager) followupAuth(authURL, hostname string) {
	for i := 0; i < 60; i++ {
		m.mu.RLock()
		client := m.client
		stopped := m.status.State != "NEEDS_AUTH"
		m.mu.RUnlock()
		if client == nil || stopped {
			return
		}

		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		_, authorized, err := client.Register(ctx, hostname, authURL)
		cancel()

		if authorized {
			m.mu.Lock()
			m.status.State = "CONNECTED"
			m.status.AuthURL = ""
			m.mu.Unlock()
			_ = m.StartSession(hostname)
			return
		}

		if err != nil {
			time.Sleep(3 * time.Second)
		}
	}
}

// SetProtectFn updates the socket protection callback.
func (m *Manager) SetProtectFn(fn func(fd int) bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.protectFn = fn
	if m.client != nil {
		m.client.SetProtectFn(fn)
	}
}

// StartSession connects to /machine/map and streams network updates.
func (m *Manager) StartSession(hostname string) error {
	m.mu.Lock()
	if m.cancelMap != nil {
		m.cancelMap()
		m.cancelMap = nil
	}
	if m.client == nil {
		m.client = NewControlClient(m.state, m.protectFn)
	} else if m.protectFn != nil {
		m.client.SetProtectFn(m.protectFn)
	}
	if m.status.State != "CONNECTED" {
		m.status.State = "CONNECTING"
	}
	client := m.client
	ctx, cancel := context.WithCancel(context.Background())
	m.cancelMap = cancel
	m.mu.Unlock()

	updateChan := make(chan *MapResponse, 16)
	go func() {
		for update := range updateChan {
			m.handleMapResponse(update)
		}
	}()

	go func() {
		defer cancel()
		defer close(updateChan)
		backoff := 2 * time.Second
		for {
			if ctx.Err() != nil {
				return
			}
			startedAt := time.Now()
			err := client.StartMapSession(ctx, hostname, updateChan)
			if ctx.Err() != nil {
				return
			}
			if time.Since(startedAt) > 15*time.Second {
				backoff = 2 * time.Second
			}
			if err != nil {
				logf("[Tailscale] map session error (reconnecting in %v): %v", backoff, err)
				m.mu.Lock()
				if m.status.SelfNode == nil {
					m.status.State = "CONNECTING"
				}
				m.status.Error = err.Error()
				m.mu.Unlock()
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(backoff):
			}
			if backoff < 15*time.Second {
				backoff += 2 * time.Second
			}
		}
	}()

	return nil
}

func (m *Manager) handleMapResponse(resp *MapResponse) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if resp.Node != nil {
		logf("[Tailscale] map response received: self IP=%v, name=%s", resp.Node.Addresses, resp.Node.Name)
		m.status.SelfNode = resp.Node
		var ips []string
		for _, addr := range resp.Node.Addresses {
			ip, _, err := net.ParseCIDR(addr)
			if err == nil {
				ips = append(ips, ip.String())
			}
		}
		m.status.TailscaleIPs = ips
		m.status.MagicDNSName = resp.Node.Name
		m.status.State = "CONNECTED"
		m.status.AuthURL = ""
		m.status.Error = ""
	}

	if resp.Node == nil && len(resp.Peers) == 0 && len(resp.PeersChanged) == 0 && len(resp.PeersRemoved) == 0 {
		return
	}

	// Update peers
	if len(resp.Peers) > 0 {
		m.peersByID = make(map[int64]*Node)
		for _, p := range resp.Peers {
			m.peersByID[p.ID] = p
		}
	}
	for _, p := range resp.PeersChanged {
		m.peersByID[p.ID] = p
	}
	for _, id := range resp.PeersRemoved {
		delete(m.peersByID, id)
	}

	// Rebuild MagicDNS table
	m.dnsRecords = make(map[string]net.IP)
	var peerSummaries []PeerInfo
	var exitNodes []ExitNode

	for _, p := range m.peersByID {
		var pIPs []string
		var firstV4 net.IP
		for _, addr := range p.Addresses {
			ip, _, err := net.ParseCIDR(addr)
			if err == nil {
				pIPs = append(pIPs, ip.String())
				if ip.To4() != nil && firstV4 == nil {
					firstV4 = ip
				}
			}
		}

		isExit := false
		for _, allowed := range p.AllowedIPs {
			if allowed == "0.0.0.0/0" || allowed == "::/0" {
				isExit = true
				break
			}
		}

		online := false
		if p.Online != nil {
			online = *p.Online
		}

		var lastSeen int64
		if p.LastSeen != nil {
			lastSeen = p.LastSeen.Unix()
		}

		cleanName := strings.TrimSuffix(p.Name, ".")
		if firstV4 != nil {
			m.dnsRecords[strings.ToLower(cleanName)] = firstV4
			parts := strings.Split(cleanName, ".")
			if len(parts) > 0 {
				m.dnsRecords[strings.ToLower(parts[0])] = firstV4
			}
		}

		osName := ""
		if p.Hostinfo != nil {
			osName = p.Hostinfo.OS
		}

		peerSummaries = append(peerSummaries, PeerInfo{
			ID:       p.ID,
			Name:     cleanName,
			IPs:      pIPs,
			OS:       osName,
			Online:   online,
			LastSeen: lastSeen,
			IsExit:   isExit,
		})

		if isExit && firstV4 != nil {
			exitNodes = append(exitNodes, ExitNode{
				ID:   p.ID,
				Name: cleanName,
				IP:   firstV4.String(),
			})
		}
	}

	m.status.Peers = peerSummaries
	m.status.ExitNodes = exitNodes
}

// ResolveMagicDNS resolves a hostname (e.g. "nas" or "nas.tailxxxx.ts.net") from the MagicDNS table.
func (m *Manager) ResolveMagicDNS(name string) net.IP {
	m.mu.RLock()
	defer m.mu.RUnlock()
	clean := strings.ToLower(strings.TrimSuffix(name, "."))
	return m.dnsRecords[clean]
}

// MatchPeer checks if an IP belongs to any known Tailscale peer using Longest Prefix Match.
func (m *Manager) MatchPeer(ip net.IP) *Node {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var bestPeer *Node
	bestMaskLen := -1

	for _, p := range m.peersByID {
		for _, allowed := range p.AllowedIPs {
			_, cidr, err := net.ParseCIDR(allowed)
			if err == nil && cidr.Contains(ip) {
				ones, _ := cidr.Mask.Size()
				if ones > bestMaskLen {
					bestMaskLen = ones
					bestPeer = p
				}
			}
		}
	}
	return bestPeer
}

// SetExitNode selects an exit node by its ID (0 to disable).
func (m *Manager) SetExitNode(id int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.activeExitID = id
	m.status.ActiveExitID = id
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
	m.status.State = "DISCONNECTED"
	m.status.AuthURL = ""
	m.mu.Unlock()
}
