package tunnel

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/nqmgaming/blockads-tunnel/internal/proxy"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/httpproxy"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/hysteria2"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/masque"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/socks"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/ssh"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/trojan"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/tuic"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/vless"
	"github.com/nqmgaming/blockads-tunnel/internal/proxy/vmess"
	"github.com/nqmgaming/blockads-tunnel/internal/shadowsocks"
)

// TestProxyNodeDelay measures real-world HTTP 204 response delay through a proxy node.
// Returns the round-trip latency in milliseconds, or -1 and error if the test fails.
func TestProxyNodeDelay(protocol string, configJSON string, timeoutMs int) (int64, error) {
	return testProxyNodeDelayWithProtect(protocol, configJSON, timeoutMs, nil)
}

// TestProxyNodeDelayProtected measures HTTP 204 response delay through a proxy node with socket protection.
func TestProxyNodeDelayProtected(protocol string, configJSON string, timeoutMs int, protector SocketProtector) (int64, error) {
	var protectFn func(fd int) bool
	if protector != nil {
		protectFn = func(fd int) bool { return protector.Protect(fd) }
	}
	return testProxyNodeDelayWithProtect(protocol, configJSON, timeoutMs, protectFn)
}

// TestProxyDelay on Engine uses the engine's protect function to ensure test packets bypass VPN tunnel.
func (e *Engine) TestProxyDelay(protocol string, configJSON string, timeoutMs int) (int64, error) {
	return testProxyNodeDelayWithProtect(protocol, configJSON, timeoutMs, e.protectFn)
}

func testProxyNodeDelayWithProtect(protocol string, configJSON string, timeoutMs int, protectFn func(fd int) bool) (int64, error) {
	protocol = strings.ToLower(strings.TrimSpace(protocol))
	if configJSON == "" || protocol == "" {
		return -1, fmt.Errorf("empty protocol or config")
	}

	if timeoutMs <= 0 {
		timeoutMs = 5000
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond

	var client proxy.Client
	var err error

	switch protocol {
	case "shadowsocks", "ss":
		cfg, parseErr := shadowsocks.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		client, err = shadowsocks.NewClient(cfg, protectFn)

	case "trojan":
		cfg, parseErr := trojan.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		client, err = trojan.NewClient(cfg, protectFn)

	case "vless":
		cfg, parseErr := vless.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		client, err = vless.NewClient(cfg, protectFn)

	case "vmess":
		cfg, parseErr := vmess.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		client, err = vmess.NewClient(cfg, protectFn)

	case "hysteria2", "hy2":
		cfg, parseErr := hysteria2.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		client, err = hysteria2.NewClient(cfg, protectFn)

	case "tuic":
		cfg, parseErr := tuic.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		client, err = tuic.NewClient(cfg, protectFn)

	case "masque":
		cfg, parseErr := masque.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		masqueClient, mErr := masque.NewClient(cfg, protectFn)
		if mErr != nil {
			return -1, fmt.Errorf("init client failed: %w", mErr)
		}
		defer masqueClient.Close()

		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		defer cancel()
		duration, pingErr := masqueClient.Ping(ctx)
		if pingErr != nil {
			return -1, fmt.Errorf("masque ping failed: %w", pingErr)
		}
		return duration.Milliseconds(), nil

	case "socks5", "socks", "socks4", "socks4a":
		cfg, parseErr := socks.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		if protocol == "socks4" || protocol == "socks4a" {
			cfg.Version = protocol
		}
		client, err = socks.NewClient(cfg, protectFn)

	case "http", "https":
		cfg, parseErr := httpproxy.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		if protocol == "https" {
			cfg.TLS = true
		}
		client, err = httpproxy.NewClient(cfg, protectFn)

	case "ssh":
		cfg, parseErr := ssh.FromJSON(configJSON)
		if parseErr != nil {
			return -1, parseErr
		}
		client, err = ssh.NewClient(cfg, protectFn)

	default:
		return -1, fmt.Errorf("unsupported protocol: %s", protocol)
	}

	if err != nil {
		return -1, fmt.Errorf("init client failed: %w", err)
	}
	if closer, ok := client.(interface{ Close() error }); ok {
		defer closer.Close()
	}

	type delayEndpoint struct {
		ip   net.IP
		host string
	}
	endpoints := []delayEndpoint{
		{ip: net.ParseIP("104.16.132.229"), host: "cp.cloudflare.com"},
		{ip: net.ParseIP("1.1.1.1"), host: "cp.cloudflare.com"},
		{ip: net.ParseIP("104.16.133.229"), host: "cp.cloudflare.com"},
	}

	deadline := time.Now().Add(timeout)
	var lastErr error

	for _, ep := range endpoints {
		if time.Now().After(deadline) {
			break
		}
		perAttemptTimeout := time.Until(deadline)
		if perAttemptTimeout > 4*time.Second && len(endpoints) > 1 {
			perAttemptTimeout = 4 * time.Second
		}

		start := time.Now()
		conn, err := client.DialTCP(ep.ip, 80)
		if err != nil {
			lastErr = fmt.Errorf("dial proxy (%s): %w", ep.host, err)
			continue
		}

		_ = conn.SetDeadline(time.Now().Add(perAttemptTimeout))

		req := fmt.Sprintf("GET /generate_204 HTTP/1.1\r\nHost: %s\r\nUser-Agent: BlockAds/1.0\r\nConnection: close\r\n\r\n", ep.host)
		if _, err := conn.Write([]byte(req)); err != nil {
			_ = conn.Close()
			lastErr = fmt.Errorf("write http request (%s): %w", ep.host, err)
			continue
		}

		resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
		if err != nil {
			_ = conn.Close()
			lastErr = fmt.Errorf("read http response (%s): %w", ep.host, err)
			continue
		}
		_ = resp.Body.Close()
		_ = conn.Close()

		if resp.StatusCode == 204 || resp.StatusCode == 200 {
			latency := time.Since(start).Milliseconds()
			return latency, nil
		}
		lastErr = fmt.Errorf("unexpected http status %d from %s", resp.StatusCode, ep.host)
	}

	if lastErr != nil {
		return -1, lastErr
	}
	return -1, fmt.Errorf("proxy test timed out")
}
