package tailscale

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// SetTaildropReceiver registers the callback for incoming Taildrop files.
func (m *Manager) SetTaildropReceiver(rcv TaildropReceiver) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.taildropReceiver = rcv
	if m.peerAPIServer != nil {
		m.peerAPIServer.SetReceiver(rcv)
	}
}

// SetTaildropTempDir sets the directory where incoming files are temporarily staged.
func (m *Manager) SetTaildropTempDir(dir string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.taildropDir = dir
	if m.peerAPIServer != nil {
		m.peerAPIServer.SetTempDir(dir)
	}
}

// PeerAPIPort returns the active port of the PeerAPI server.
func (m *Manager) PeerAPIPort() uint16 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.peerAPIServer != nil {
		return m.peerAPIServer.Port()
	}
	return 0
}

// startPeerAPILocked starts the PeerAPI server on the DataPlane if not already running.
func (m *Manager) startPeerAPILocked() {
	if m.dataPlane == nil {
		return
	}
	if m.peerAPIServer == nil {
		m.peerAPIServer = NewPeerAPIServer(DefaultPeerAPIPort, m.taildropDir, m.taildropReceiver)
	}
	if err := m.peerAPIServer.Start(m.dataPlane); err != nil {
		logf("[Tailscale/PeerAPI] start failed: %v", err)
	}
}

// stopPeerAPILocked stops the PeerAPI server.
func (m *Manager) stopPeerAPILocked() {
	if m.peerAPIServer != nil {
		m.peerAPIServer.Stop()
	}
}

// SendTaildropFile sends a local file to a peer's PeerAPI Taildrop endpoint over the Tailscale netstack.
func (m *Manager) SendTaildropFile(target string, fileName string, filePath string) error {
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
		return fmt.Errorf("peer %s not found", target)
	}
	if selfNode != nil && targetPeer.ID == selfNode.ID {
		return errors.New("cannot send file to self device")
	}
	if targetPeer.Online != nil && !*targetPeer.Online {
		return errors.New("peer is offline")
	}
	if dp == nil {
		return errors.New("dataplane not ready")
	}

	var ipv4, ipv6 net.IP
	for _, cidr := range targetPeer.Addresses {
		ip, _, err := net.ParseCIDR(cidr)
		if err != nil {
			ip = net.ParseIP(cidr)
		}
		if ip == nil {
			continue
		}
		if v4 := ip.To4(); v4 != nil && ipv4 == nil {
			ipv4 = v4
		} else if ipv6 == nil {
			ipv6 = ip
		}
	}

	dstIP := ipv4
	protoName := "peerapi4"
	if dstIP == nil {
		dstIP = ipv6
		protoName = "peerapi6"
	}
	if dstIP == nil {
		return errors.New("peer has no Tailscale IP address")
	}

	peerPort := DefaultPeerAPIPort
	if targetPeer.Hostinfo != nil {
		for _, svc := range targetPeer.Hostinfo.Services {
			if svc.Proto == protoName && svc.Port > 0 {
				peerPort = svc.Port
				break
			}
		}
	}

	f, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("open file: %w", err)
	}
	defer f.Close()

	stat, err := f.Stat()
	if err != nil {
		return fmt.Errorf("stat file: %w", err)
	}

	cleanName := filepath.Base(strings.TrimSpace(fileName))
	if cleanName == "" || cleanName == "." || cleanName == "/" {
		cleanName = filepath.Base(filePath)
	}

	endpoint := fmt.Sprintf("http://%s/v0/put/%s", net.JoinHostPort(dstIP.String(), strconv.Itoa(int(peerPort))), url.PathEscape(cleanName))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPut, endpoint, f)
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.ContentLength = stat.Size()

	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(dialCtx context.Context, _, _ string) (net.Conn, error) {
				return dp.DialTCP(dialCtx, dstIP, int(peerPort), targetPeer)
			},
			ResponseHeaderTimeout: 30 * time.Second,
		},
	}

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("send file: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("peer rejected file (%d): %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}

	logf("[Tailscale/PeerAPI] Sent Taildrop file %s (%d bytes) to %s:%d", cleanName, stat.Size(), dstIP, peerPort)
	return nil
}
