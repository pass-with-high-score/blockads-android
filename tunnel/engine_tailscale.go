package tunnel

import (
	"net"
	"strings"

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

	mgr := tailscale.NewManager(st, e.protectFn)
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
