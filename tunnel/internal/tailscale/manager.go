package tailscale

import (
	"context"
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
	ID         int64    `json:"id"`
	Name       string   `json:"name"`
	IPs        []string `json:"ips"`
	IPv4       string   `json:"ipv4,omitempty"`
	IPv6       string   `json:"ipv6,omitempty"`
	OS         string   `json:"os,omitempty"`
	Online     bool     `json:"online"`
	LastSeen   int64    `json:"last_seen,omitempty"`
	IsExit     bool     `json:"is_exit_node"`
	IsSelf     bool     `json:"is_self"`
	KeyExpiry  int64    `json:"key_expiry,omitempty"`
	KeyExpired bool     `json:"key_expired,omitempty"`
	CurAddr    string   `json:"cur_addr,omitempty"`
	Relay      string   `json:"relay,omitempty"`
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
	derpMap      *DERPMap
	dataPlane    *DataPlane

	hostname                string
	lastAdvertisedEndpoints []string
	lastAdvertisedDERP      int

	taildropReceiver TaildropReceiver
	taildropDir      string
	peerAPIServer    *PeerAPIServer
}

// NewManager creates a Tailscale Manager.
func NewManager(state *State, protectFn func(fd int) bool) *Manager {
	dp, err := NewDataPlane(state, protectFn)
	if err != nil {
		logf("[Tailscale] failed to init dataplane: %v", err)
	}
	return &Manager{
		state:      state,
		protectFn:  protectFn,
		peersByID:  make(map[int64]*Node),
		dnsRecords: make(map[string]net.IP),
		dataPlane:  dp,
		status: Status{
			State: "DISCONNECTED",
		},
	}
}

// DataPlane returns the active Tailscale DataPlane.
func (m *Manager) DataPlane() *DataPlane {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.dataPlane
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
	if m.dataPlane != nil {
		m.dataPlane.SetProtectFn(fn)
	}
}

// StartSession connects to /machine/map and streams network updates.
func (m *Manager) StartSession(hostname string) error {
	m.mu.Lock()
	m.hostname = hostname
	if m.cancelMap != nil {
		m.cancelMap()
		m.cancelMap = nil
	}
	if m.client == nil {
		m.client = NewControlClient(m.state, m.protectFn)
	} else if m.protectFn != nil {
		m.client.SetProtectFn(m.protectFn)
	}
	m.client.SetPeerAPIPort(DefaultPeerAPIPort)
	m.startPeerAPILocked()
	if m.dataPlane != nil && m.dataPlane.Bind() != nil {
		m.client.SetNetMeta(m.dataPlane.Bind().LocalEndpoints(), m.dataPlane.Bind().HomeDERP())
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

	if resp.DERPMap != nil {
		m.derpMap = resp.DERPMap
		logf("[Tailscale/DERP] Received DERPMap with %d regions", len(resp.DERPMap.Regions))
	}

	if resp.Node == nil && len(resp.Peers) == 0 && len(resp.PeersChanged) == 0 && len(resp.PeersRemoved) == 0 && resp.DERPMap == nil {
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
	if m.status.SelfNode != nil {
		var selfV4 net.IP
		for _, addr := range m.status.SelfNode.Addresses {
			if ip, _, err := net.ParseCIDR(addr); err == nil && ip.To4() != nil {
				selfV4 = ip.To4()
				break
			}
		}
		if selfV4 != nil {
			cleanSelf := strings.TrimSuffix(m.status.SelfNode.Name, ".")
			if cleanSelf != "" {
				m.dnsRecords[strings.ToLower(cleanSelf)] = selfV4
				if parts := strings.Split(cleanSelf, "."); len(parts) > 0 && parts[0] != "" {
					m.dnsRecords[strings.ToLower(parts[0])] = selfV4
				}
			}
		}
	}

	var peerSummaries []PeerInfo
	var exitNodes []ExitNode

	allNodes := make([]*Node, 0, len(m.peersByID)+1)
	if m.status.SelfNode != nil {
		allNodes = append(allNodes, m.status.SelfNode)
	}
	for _, p := range m.peersByID {
		allNodes = append(allNodes, p)
	}

	for _, p := range allNodes {
		logf("[Tailscale] peer %s (id=%d) endpoints=%v DERP=%s HomeDERP=%d", p.Name, p.ID, p.Endpoints, p.DERP, p.HomeDERP)
		var pIPs []string
		var firstV4 net.IP
		var firstV6 net.IP
		for _, addr := range p.Addresses {
			ip, _, err := net.ParseCIDR(addr)
			if err != nil {
				ip = net.ParseIP(addr)
			}
			if ip != nil {
				pIPs = append(pIPs, ip.String())
				if ip.To4() != nil && firstV4 == nil {
					firstV4 = ip.To4()
				} else if ip.To4() == nil && firstV6 == nil {
					firstV6 = ip
				}
			}
		}

		var keyExpiry int64
		var keyExpired bool
		if p.KeyExpiry != nil {
			keyExpiry = p.KeyExpiry.Unix()
			keyExpired = time.Now().After(*p.KeyExpiry)
		}
		if p.KeyExpired {
			keyExpired = true
		}

		var curAddr string
		var relay string
		if m.dataPlane != nil && m.dataPlane.Bind() != nil {
			cAddr, derpID := m.dataPlane.Bind().PeerRouteInfo(p.ID)
			curAddr = cAddr
			if derpID > 0 {
				if m.derpMap != nil && m.derpMap.Regions[derpID] != nil {
					relay = m.derpMap.Regions[derpID].RegionCode
				}
				if relay == "" {
					relay = fmt.Sprintf("DERP #%d", derpID)
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
			if cleanName != "" {
				m.dnsRecords[strings.ToLower(cleanName)] = firstV4
				parts := strings.Split(cleanName, ".")
				if len(parts) > 0 && parts[0] != "" {
					m.dnsRecords[strings.ToLower(parts[0])] = firstV4
				}
			}
			if p.Hostinfo != nil && p.Hostinfo.Hostname != "" {
				m.dnsRecords[strings.ToLower(strings.TrimSuffix(p.Hostinfo.Hostname, "."))] = firstV4
			}
		}

		osName := ""
		if p.Hostinfo != nil {
			osName = p.Hostinfo.OS
		}

		var v4Str, v6Str string
		if firstV4 != nil {
			v4Str = firstV4.String()
		}
		if firstV6 != nil {
			v6Str = firstV6.String()
		}

		isSelf := false
		if m.status.SelfNode != nil && p.ID == m.status.SelfNode.ID {
			isSelf = true
			online = true
			if osName == "" {
				osName = "Android"
			}
		}

		peerSummaries = append(peerSummaries, PeerInfo{
			ID:         p.ID,
			Name:       cleanName,
			IPs:        pIPs,
			IPv4:       v4Str,
			IPv6:       v6Str,
			OS:         osName,
			Online:     online,
			LastSeen:   lastSeen,
			IsExit:     isExit,
			IsSelf:     isSelf,
			KeyExpiry:  keyExpiry,
			KeyExpired: keyExpired,
			CurAddr:    curAddr,
			Relay:      relay,
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

	if m.dataPlane != nil {
		_ = m.dataPlane.Sync(m.status.SelfNode, m.peersByID, m.derpMap, m.activeExitID)
		if m.client != nil && m.dataPlane.Bind() != nil {
			endpoints := m.dataPlane.Bind().LocalEndpoints()
			homeDERP := m.dataPlane.Bind().HomeDERP()
			m.client.SetNetMeta(endpoints, homeDERP)
			m.maybeAdvertiseEndpointsLocked(endpoints, homeDERP)
		}
	}
}
