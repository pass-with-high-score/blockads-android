package tailscale

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"golang.org/x/net/http2"
)

// StartMapSession starts long-running subscription to /machine/map and feeds updates into updateChan.
func (c *ControlClient) StartMapSession(ctx context.Context, hostname string, updateChan chan<- *MapResponse) error {
	noiseConn, err := c.dialNoiseTLS(ctx)
	if err != nil {
		return err
	}

	c.mu.Lock()
	c.rawConn = noiseConn
	c.running = true
	c.mu.Unlock()

	defer func() {
		c.mu.Lock()
		c.running = false
		if c.rawConn != nil {
			c.rawConn.Close()
			c.rawConn = nil
		}
		c.mu.Unlock()
	}()

	c.mu.Lock()
	endpoints := append([]string(nil), c.endpoints...)
	prefDERP := c.preferredDERP
	if prefDERP <= 0 {
		prefDERP = 1
	}
	peerPort := c.peerAPIPort
	c.mu.Unlock()

	var svcs []Service
	if peerPort > 0 {
		svcs = []Service{
			{Proto: "peerapi4", Port: peerPort},
			{Proto: "peerapi6", Port: peerPort},
		}
	}

	mapReq := MapRequest{
		Version:   105,
		KeepAlive: true,
		NodeKey:   c.state.NodeKey().PublicHex("nodekey:"),
		DiscoKey:  c.state.DiscoKey().PublicHex("discokey:"),
		Endpoints: endpoints,
		Stream:    true,
		OmitPeers: false,
		Hostinfo: &Hostinfo{
			OS:       "android",
			Hostname: hostname,
			NetInfo: &NetInfo{
				PreferredDERP: prefDERP,
			},
			Services: svcs,
		},
	}

	reqBytes, err := json.Marshal(mapReq)
	if err != nil {
		return err
	}

	client := &http.Client{
		Transport: &http2.Transport{
			AllowHTTP: true,
			DialTLSContext: func(ctx context.Context, network, addr string, cfg *tls.Config) (net.Conn, error) {
				return noiseConn, nil
			},
		},
	}

	mapURL := strings.TrimRight(c.controlURL, "/") + "/machine/map"
	httpReq, err := http.NewRequestWithContext(ctx, "POST", mapURL, bytes.NewReader(reqBytes))
	if err != nil {
		return err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(httpReq)
	if err != nil {
		return fmt.Errorf("map stream request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("map stream HTTP %d: %s", resp.StatusCode, string(b))
	}

	br := bufio.NewReader(resp.Body)
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		var lenBuf [4]byte
		if _, err := io.ReadFull(br, lenBuf[:]); err != nil {
			return err
		}
		frameLen := binary.LittleEndian.Uint32(lenBuf[:])
		if frameLen == 0 || frameLen > 16*1024*1024 {
			continue
		}

		frameBytes := make([]byte, frameLen)
		if _, err := io.ReadFull(br, frameBytes); err != nil {
			return err
		}

		var mapResp MapResponse
		if err := json.Unmarshal(frameBytes, &mapResp); err != nil {
			logf("[Tailscale] map unmarshal error: %v", err)
			continue
		}

		select {
		case updateChan <- &mapResp:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}

// UpdateEndpoints advertises updated local endpoints and preferred DERP region to the control plane.
func (c *ControlClient) UpdateEndpoints(ctx context.Context, hostname string, endpoints []string, prefDERP int) error {
	c.mu.Lock()
	c.endpoints = append([]string(nil), endpoints...)
	if prefDERP > 0 {
		c.preferredDERP = prefDERP
	}
	pref := c.preferredDERP
	if pref <= 0 {
		pref = 1
	}
	peerPort := c.peerAPIPort
	c.mu.Unlock()

	var svcs []Service
	if peerPort > 0 {
		svcs = []Service{
			{Proto: "peerapi4", Port: peerPort},
			{Proto: "peerapi6", Port: peerPort},
		}
	}

	mapReq := MapRequest{
		Version:   105,
		KeepAlive: false,
		NodeKey:   c.state.NodeKey().PublicHex("nodekey:"),
		DiscoKey:  c.state.DiscoKey().PublicHex("discokey:"),
		Endpoints: endpoints,
		Stream:    false,
		OmitPeers: true,
		Hostinfo: &Hostinfo{
			OS:       "android",
			Hostname: hostname,
			NetInfo: &NetInfo{
				PreferredDERP: pref,
			},
			Services: svcs,
		},
	}

	reqBytes, err := json.Marshal(mapReq)
	if err != nil {
		return err
	}

	noiseConn, err := c.dialNoiseTLS(ctx)
	if err != nil {
		return fmt.Errorf("dial noise for endpoint update: %w", err)
	}
	defer noiseConn.Close()

	client := &http.Client{
		Transport: &http2.Transport{
			AllowHTTP: true,
			DialTLSContext: func(ctx context.Context, network, addr string, cfg *tls.Config) (net.Conn, error) {
				return noiseConn, nil
			},
		},
		Timeout: 10 * time.Second,
	}

	mapURL := strings.TrimRight(c.controlURL, "/") + "/machine/map"
	httpReq, err := http.NewRequestWithContext(ctx, "POST", mapURL, bytes.NewReader(reqBytes))
	if err != nil {
		return err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(httpReq)
	if err != nil {
		return fmt.Errorf("endpoint update request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("endpoint update HTTP %d: %s", resp.StatusCode, string(b))
	}

	_, _ = io.Copy(io.Discard, resp.Body)
	logf("[Tailscale] Advertised %d endpoints (preferredDERP=%d) to control plane", len(endpoints), pref)
	return nil
}
