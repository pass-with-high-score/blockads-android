package tunnel

import (
	"fmt"
	"net"
	"strconv"
	"strings"

	"github.com/nqmgaming/blockads-tunnel/internal/proxy"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/hysteria2"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/masque"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/trojan"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/tuic"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/vless"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/vmess"
	"github.com/nqmgaming/blockads-tunnel/internal/shadowsocks"
)

// SetProxyNodeConfig configures an outbound proxy node (shadowsocks, trojan, vless, vmess, hysteria2, tuic, masque).
// Empty string or empty protocol clears the active proxy node.
func (e *Engine) SetProxyNodeConfig(protocol string, configJSON string) error {
	protocol = strings.ToLower(strings.TrimSpace(protocol))
	if configJSON == "" || protocol == "" {
		e.proxyClient.Store(nil)
		e.ssClient.Store(nil)
		logf("Proxy node cleared")
		return nil
	}

	switch protocol {
	case "shadowsocks", "ss":
		cfg, err := shadowsocks.FromJSON(configJSON)
		if err != nil {
			return err
		}
		client, err := shadowsocks.NewClient(cfg, e.protectFn)
		if err != nil {
			return err
		}
		e.ssClient.Store(client)
		var pClient proxy.Client = client
		e.proxyClient.Store(&pClient)
		logf("Proxy node (shadowsocks) configured for %s (tag: %s)", client.ServerAddress(), client.Tag())
		return nil

	case "trojan":
		cfg, err := trojan.FromJSON(configJSON)
		if err != nil {
			return err
		}
		client, err := trojan.NewClient(cfg, e.protectFn)
		if err != nil {
			return err
		}
		e.ssClient.Store(nil)
		var pClient proxy.Client = client
		e.proxyClient.Store(&pClient)
		logf("Proxy node (trojan) configured for %s (tag: %s)", client.ServerAddress(), client.Tag())
		return nil

	case "vless":
		cfg, err := vless.FromJSON(configJSON)
		if err != nil {
			return err
		}
		client, err := vless.NewClient(cfg, e.protectFn)
		if err != nil {
			return err
		}
		e.ssClient.Store(nil)
		var pClient proxy.Client = client
		e.proxyClient.Store(&pClient)
		logf("Proxy node (vless) configured for %s (tag: %s)", client.ServerAddress(), client.Tag())
		return nil

	case "vmess":
		cfg, err := vmess.FromJSON(configJSON)
		if err != nil {
			return err
		}
		client, err := vmess.NewClient(cfg, e.protectFn)
		if err != nil {
			return err
		}
		e.ssClient.Store(nil)
		var pClient proxy.Client = client
		e.proxyClient.Store(&pClient)
		logf("Proxy node (vmess) configured for %s (tag: %s)", client.ServerAddress(), client.Tag())
		return nil

	case "hysteria2", "hy2":
		cfg, err := hysteria2.FromJSON(configJSON)
		if err != nil {
			return err
		}
		client, err := hysteria2.NewClient(cfg, e.protectFn)
		if err != nil {
			return err
		}
		e.ssClient.Store(nil)
		var pClient proxy.Client = client
		e.proxyClient.Store(&pClient)
		logf("Proxy node (hysteria2) configured for %s (tag: %s)", client.ServerAddress(), client.Tag())
		return nil

	case "tuic":
		cfg, err := tuic.FromJSON(configJSON)
		if err != nil {
			return err
		}
		client, err := tuic.NewClient(cfg, e.protectFn)
		if err != nil {
			return err
		}
		e.ssClient.Store(nil)
		var pClient proxy.Client = client
		e.proxyClient.Store(&pClient)
		logf("Proxy node (tuic) configured for %s (tag: %s)", client.ServerAddress(), client.Tag())
		return nil

	case "masque":
		cfg, err := masque.FromJSON(configJSON)
		if err != nil {
			return err
		}
		client, err := masque.NewClient(cfg, e.protectFn)
		if err != nil {
			return err
		}
		e.ssClient.Store(nil)
		var pClient proxy.Client = client
		e.proxyClient.Store(&pClient)
		logf("Proxy node (masque) configured for %s (tag: %s)", client.ServerAddress(), client.Tag())
		return nil

	default:
		return fmt.Errorf("unsupported proxy protocol: %s", protocol)
	}
}

// GetActiveProxyProtocol returns the protocol of the currently active proxy ("shadowsocks", "trojan", "vless"), or "".
func (e *Engine) GetActiveProxyProtocol() string {
	pc := e.proxyClient.Load()
	if pc != nil && *pc != nil {
		return (*pc).Protocol()
	}
	if e.ssClient.Load() != nil {
		return "shadowsocks"
	}
	return ""
}

// GetActiveProxyServerAddress returns host:port of the active proxy, or "".
func (e *Engine) GetActiveProxyServerAddress() string {
	pc := e.proxyClient.Load()
	if pc != nil && *pc != nil {
		return (*pc).ServerAddress()
	}
	if ss := e.ssClient.Load(); ss != nil {
		return ss.ServerAddress()
	}
	return ""
}

// SetShadowsocksConfig configures the Shadowsocks / Outline client.
// Kept for backward compatibility.
func (e *Engine) SetShadowsocksConfig(configJSON string) error {
	return e.SetProxyNodeConfig("shadowsocks", configJSON)
}

// GetShadowsocksConfig returns the current Shadowsocks config JSON or empty string.
func (e *Engine) GetShadowsocksConfig() string {
	client := e.ssClient.Load()
	if client == nil || client.Config() == nil {
		return ""
	}
	return client.Config().ToJSON()
}

// isProxyServer checks whether target matches the active proxy server's address.
func (e *Engine) isProxyServer(targetIP net.IP, targetPort int) bool {
	addr := e.GetActiveProxyServerAddress()
	if addr == "" {
		return false
	}
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return false
	}
	p, _ := strconv.Atoi(portStr)
	if p != 0 && p != targetPort {
		return false
	}
	if targetIP.String() == host {
		return true
	}
	if parsed := net.ParseIP(host); parsed != nil {
		return targetIP.Equal(parsed)
	}
	return false
}

func isPrivateOrLoopback(ip net.IP) bool {
	if ip == nil {
		return false
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsUnspecified()
}

// DialProxy attempts to dial target via Tailscale (for Tailnet / Exit Node traffic) or the active outbound proxy.
// Returns (conn, true, err) if handled, or (nil, false, nil) if neither is applicable.
func (e *Engine) DialProxy(targetIP net.IP, targetPort int) (net.Conn, bool, error) {
	if targetIP != nil && targetIP.IsLoopback() {
		return nil, false, nil
	}
	if conn, used, err := e.dialTailscale(targetIP, targetPort); used {
		return conn, true, err
	}
	if isPrivateOrLoopback(targetIP) {
		return nil, false, nil
	}
	if e.isProxyServer(targetIP, targetPort) {
		logf("[Engine] DialProxy: skipped dial to proxy server itself (%s:%d)", targetIP, targetPort)
		return nil, false, nil
	}
	if pc := e.proxyClient.Load(); pc != nil && *pc != nil {
		conn, err := (*pc).DialTCP(targetIP, targetPort)
		return conn, true, err
	}
	if ss := e.ssClient.Load(); ss != nil {
		conn, err := ss.DialTCP(targetIP, targetPort)
		return conn, true, err
	}
	return nil, false, nil
}


