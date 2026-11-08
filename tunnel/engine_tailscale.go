package tunnel

import (
	"context"
	"fmt"
	"net"
	"strings"
	"time"

	"github.com/nqmgaming/blockads-tunnel/internal/tailscale"
)

// ConfigureTailscale initializes or loads Tailscale state with optional authKey and controlURL.
// Returns the updated state JSON string to be saved by Kotlin DataStore.
func (e *Engine) ConfigureTailscale(stateJSON, controlURL, authKey string) string {
	var st *tailscale.State
	var err error

	if stateJSON != "" {
		st, err = tailscale.StateFromJSON(stateJSON)
	}
	if err != nil || st == nil {
		st, err = tailscale.NewState(controlURL)
		if err != nil {
			logf("[Tailscale] failed to create state: %v", err)
			return "{}"
		}
	}

	if controlURL != "" {
		st.ControlURL = controlURL
	}
	if authKey != "" {
		st.AuthKey = authKey
	}

	e.mu.Lock()
	protectFn := e.protectFn
	rcv := e.taildropReceiver
	tdDir := e.taildropDir
	e.mu.Unlock()

	mgr := tailscale.NewManager(st, protectFn)
	if rcv != nil {
		mgr.SetTaildropReceiver(rcv)
	}
	if tdDir != "" {
		mgr.SetTaildropTempDir(tdDir)
	}
	if old := e.tsManager.Swap(mgr); old != nil {
		old.Stop()
	}
	logf("[Tailscale] manager configured (control: %s)", st.ControlURL)
	return st.ToJSON()
}

// StartTailscaleAuth initiates machine registration with the control plane.
// Returns AuthURL (if user needs to sign in via browser) or empty string on immediate success.
func (e *Engine) StartTailscaleAuth(hostname string) string {
	mgr := e.tsManager.Load()
	if mgr == nil {
		logf("[Tailscale] cannot auth: manager not configured")
		return ""
	}
	authURL, err := mgr.StartAuth("", "", hostname)
	if err != nil {
		logf("[Tailscale] auth error: %v", err)
		return ""
	}
	if authURL != "" {
		logf("[Tailscale] interactive login required: %s", authURL)
		return authURL
	}
	logf("[Tailscale] machine authorized, starting map session")
	_ = mgr.StartSession(hostname)
	return ""
}

// StartTailscaleSession connects to /machine/map and starts receiving network updates.
func (e *Engine) StartTailscaleSession(hostname string) error {
	mgr := e.tsManager.Load()
	if mgr == nil {
		return nil
	}
	return mgr.StartSession(hostname)
}

// StopTailscale stops the Tailscale session and disconnects.
func (e *Engine) StopTailscale() {
	if mgr := e.tsManager.Swap(nil); mgr != nil {
		mgr.Stop()
		logf("[Tailscale] stopped")
	}
}

// GetTailscaleStatus returns the current connection status as a JSON string for the UI.
func (e *Engine) GetTailscaleStatus() string {
	mgr := e.tsManager.Load()
	if mgr == nil {
		return `{"state":"DISCONNECTED"}`
	}
	return mgr.GetStatusJSON()
}

// GetTailscaleState returns the serialized cryptographic state.
func (e *Engine) GetTailscaleState() string {
	mgr := e.tsManager.Load()
	if mgr == nil {
		return "{}"
	}
	return mgr.GetStateJSON()
}

// SetTailscaleExitNode sets the active exit node peer ID (0 to disable).
func (e *Engine) SetTailscaleExitNode(nodeID int64) {
	if mgr := e.tsManager.Load(); mgr != nil {
		mgr.SetExitNode(nodeID)
		logf("[Tailscale] active exit node set to: %d", nodeID)
	}
}

// IsTailscaleActive returns true if Tailscale is active and configured.
func (e *Engine) IsTailscaleActive() bool {
	return e.tsManager.Load() != nil
}

// resolveTailscaleMagicDNS checks if domain resolves to a Tailscale peer IP.
func (e *Engine) resolveTailscaleMagicDNS(domain string) net.IP {
	mgr := e.tsManager.Load()
	if mgr == nil {
		return nil
	}
	clean := strings.ToLower(strings.TrimSuffix(domain, "."))
	return mgr.ResolveMagicDNS(clean)
}

// matchTailscalePeer checks if IP belongs to any known Tailscale peer.
func (e *Engine) matchTailscalePeer(ip net.IP) bool {
	mgr := e.tsManager.Load()
	if mgr == nil {
		return false
	}
	return mgr.MatchPeer(ip) != nil
}

// PingTailscalePeer tests reachability and latency to a Tailscale peer by ID or IP.
// Returns JSON string: {"latency_ms": 15, "direct": true, "relay": "sin"} or {"error": "..."}
func (e *Engine) PingTailscalePeer(target string) string {
	mgr := e.tsManager.Load()
	if mgr == nil {
		return `{"error":"Tailscale not running"}`
	}
	rttMs, isDirect, relay, err := mgr.PingPeer(target)
	if err != nil {
		return fmt.Sprintf(`{"error":%q}`, err.Error())
	}
	return fmt.Sprintf(`{"latency_ms":%d,"direct":%t,"relay":%q}`, rttMs, isDirect, relay)
}

// TaildropReceiver is a callback invoked when a file is received via Taildrop.
type TaildropReceiver interface {
	OnFileReceived(fileName string, tempPath string, size int64) bool
}

// SetTailscaleTaildropReceiver registers a callback to handle incoming Taildrop files.
func (e *Engine) SetTailscaleTaildropReceiver(receiver TaildropReceiver) {
	e.mu.Lock()
	e.taildropReceiver = receiver
	mgr := e.tsManager.Load()
	e.mu.Unlock()

	if mgr != nil {
		mgr.SetTaildropReceiver(receiver)
	}
}

// SetTailscaleTaildropDir configures the directory where incoming Taildrop files are temporarily stored.
func (e *Engine) SetTailscaleTaildropDir(dir string) {
	e.mu.Lock()
	e.taildropDir = dir
	mgr := e.tsManager.Load()
	e.mu.Unlock()

	if mgr != nil {
		mgr.SetTaildropTempDir(dir)
	}
}

// dialTailscale routes a TCP connection through the Tailscale DataPlane when the destination
// matches a Tailscale peer, an active Exit Node, or falls within the Tailscale CGNAT range.
func (e *Engine) dialTailscale(targetIP net.IP, targetPort int) (net.Conn, bool, error) {
	mgr := e.tsManager.Load()
	if mgr == nil {
		return nil, false, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return mgr.DialTCP(ctx, targetIP, targetPort)
}

// dialTailscaleUDP routes a UDP connection through the Tailscale DataPlane when the destination
// matches a Tailscale peer, an active Exit Node, or falls within the Tailscale CGNAT range.
func (e *Engine) dialTailscaleUDP(targetIP net.IP, targetPort int) (net.Conn, bool, error) {
	mgr := e.tsManager.Load()
	if mgr == nil {
		return nil, false, nil
	}
	return mgr.DialUDP(targetIP, targetPort)
}
