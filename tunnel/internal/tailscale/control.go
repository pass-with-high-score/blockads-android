package tailscale

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/net/http2"
)

// ControlClient connects to the Tailscale / Headscale control server.
type ControlClient struct {
	state      *State
	protectFn  func(fd int) bool
	controlURL string
	serverKey  [32]byte
	insecure   bool

	mu      sync.Mutex
	session *NoiseIKSession
	rawConn net.Conn
	running bool
}

// NewControlClient creates a new ControlClient.
func NewControlClient(state *State, protectFn func(fd int) bool) *ControlClient {
	return &ControlClient{
		state:      state,
		protectFn:  protectFn,
		controlURL: state.ControlURL,
	}
}

// SetInsecure allows self-signed TLS certificates (useful for testing or private Headscale).
func (c *ControlClient) SetInsecure(insecure bool) {
	c.insecure = insecure
}

// SetProtectFn updates the socket protection callback.
func (c *ControlClient) SetProtectFn(fn func(fd int) bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.protectFn = fn
}

// FetchServerPublicKey queries GET /key?v=105 to obtain the control server's static Curve25519 public key.
func (c *ControlClient) FetchServerPublicKey(ctx context.Context) error {
	u, err := url.Parse(c.controlURL)
	if err != nil {
		return fmt.Errorf("parse control URL: %w", err)
	}
	u.Path = "/key"
	q := u.Query()
	q.Set("v", "105")
	u.RawQuery = q.Encode()

	req, err := http.NewRequestWithContext(ctx, "GET", u.String(), nil)
	if err != nil {
		return err
	}

	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
	}
	if c.protectFn != nil {
		dialer.Control = func(network, address string, cConn syscall.RawConn) error {
			return cConn.Control(func(fd uintptr) {
				c.protectFn(int(fd))
			})
		}
	}

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			conn, err := dialer.DialContext(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			if c.protectFn != nil {
				if sc, ok := conn.(syscallConn); ok {
					if raw, err := sc.SyscallConn(); err == nil {
						_ = raw.Control(func(fd uintptr) {
							c.protectFn(int(fd))
						})
					}
				}
			}
			return conn, nil
		},
		TLSClientConfig: &tls.Config{
			ServerName:         u.Hostname(),
			InsecureSkipVerify: c.insecure,
		},
	}

	client := &http.Client{Transport: transport, Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("fetch /key failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GET /key returned HTTP %d", resp.StatusCode)
	}

	var keyResp ControlKeyResponse
	if err := json.NewDecoder(resp.Body).Decode(&keyResp); err != nil {
		return fmt.Errorf("decode /key response: %w", err)
	}

	rawHex := strings.TrimPrefix(keyResp.PublicKey, "mkey:")
	if rawHex == "" {
		rawHex = strings.TrimPrefix(keyResp.LegacyPublicKey, "mkey:")
	}
	b, err := hex.DecodeString(rawHex)
	if err != nil || len(b) != 32 {
		return fmt.Errorf("invalid server key in /key: %s", keyResp.PublicKey)
	}
	copy(c.serverKey[:], b)
	return nil
}

type syscallConn interface {
	SyscallConn() (rawConn, error)
}

type rawConn interface {
	Control(f func(fd uintptr)) error
}

type bufferedConn struct {
	r io.Reader
	net.Conn
}

func (b *bufferedConn) Read(p []byte) (int, error) {
	return b.r.Read(p)
}

// dialNoiseTLS dials the controlplane over TLS and establishes the Noise IK session.
func (c *ControlClient) dialNoiseTLS(ctx context.Context) (*NoiseConn, error) {
	if c.serverKey == [32]byte{} {
		if err := c.FetchServerPublicKey(ctx); err != nil {
			return nil, err
		}
	}

	u, err := url.Parse(c.controlURL)
	if err != nil {
		return nil, err
	}

	port := u.Port()
	if port == "" {
		if u.Scheme == "http" {
			port = "80"
		} else {
			port = "443"
		}
	}
	targetAddr := net.JoinHostPort(u.Hostname(), port)

	dialer := &net.Dialer{
		Timeout: 10 * time.Second,
	}
	if c.protectFn != nil {
		dialer.Control = func(network, address string, cConn syscall.RawConn) error {
			return cConn.Control(func(fd uintptr) {
				c.protectFn(int(fd))
			})
		}
	}
	rawConn, err := dialer.DialContext(ctx, "tcp", targetAddr)
	if err != nil {
		return nil, fmt.Errorf("dial control server %s: %w", targetAddr, err)
	}

	tlsConn := tls.Client(rawConn, &tls.Config{
		ServerName:         u.Hostname(),
		InsecureSkipVerify: c.insecure,
		NextProtos:         []string{"http/1.1"},
	})
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		rawConn.Close()
		return nil, fmt.Errorf("tls handshake: %w", err)
	}

	noiseSess, err := NewNoiseIKClient(1, c.state.MachineKey(), c.serverKey)
	if err != nil {
		tlsConn.Close()
		return nil, err
	}

	msg1, err := noiseSess.CreateHandshakeMessage(1)
	if err != nil {
		tlsConn.Close()
		return nil, err
	}

	b64Msg1 := base64.StdEncoding.EncodeToString(msg1)
	reqStr := fmt.Sprintf("POST /ts2021 HTTP/1.1\r\n"+
		"Host: %s\r\n"+
		"Upgrade: tailscale-control-protocol\r\n"+
		"Connection: Upgrade\r\n"+
		"X-Tailscale-Handshake: %s\r\n"+
		"Content-Length: 0\r\n\r\n", u.Hostname(), b64Msg1)

	if _, err := io.WriteString(tlsConn, reqStr); err != nil {
		tlsConn.Close()
		return nil, err
	}

	br := bufio.NewReader(tlsConn)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		tlsConn.Close()
		return nil, fmt.Errorf("read handshake response: %w", err)
	}

	if resp.StatusCode != http.StatusSwitchingProtocols {
		body, _ := io.ReadAll(resp.Body)
		tlsConn.Close()
		return nil, fmt.Errorf("unexpected status %d: %s", resp.StatusCode, string(body))
	}

	handshakeRespHeader := resp.Header.Get("X-Tailscale-Handshake")
	if handshakeRespHeader == "" {
		handshakeRespHeader = resp.Header.Get("Sec-Tailscale-Handshake")
	}

	var msg2 []byte
	if handshakeRespHeader != "" {
		var err error
		msg2, err = base64.StdEncoding.DecodeString(handshakeRespHeader)
		if err != nil {
			tlsConn.Close()
			return nil, fmt.Errorf("decode msg2 header: %w", err)
		}
	} else {
		// Response msg2 is sent directly over the upgraded socket stream (51 bytes)
		rawBuf := make([]byte, 51)
		if _, err := io.ReadFull(br, rawBuf); err != nil {
			tlsConn.Close()
			return nil, fmt.Errorf("read msg2 from stream: %w", err)
		}
		msg2 = rawBuf
	}

	if err := noiseSess.ProcessHandshakeResponse(msg2); err != nil {
		tlsConn.Close()
		return nil, fmt.Errorf("process msg2: %w", err)
	}

	return NewNoiseConn(noiseSess, &bufferedConn{r: br, Conn: tlsConn}), nil
}

// Register registers the machine with the control plane.
// If followup is non-empty, it waits for the interactive login to be authorized.
// Returns AuthURL, authorized boolean, and error.
func (c *ControlClient) Register(ctx context.Context, hostname string, followup string) (string, bool, error) {
	noiseConn, err := c.dialNoiseTLS(ctx)
	if err != nil {
		return "", false, err
	}
	defer noiseConn.Close()

	if hostname == "" {
		hostname = "blockads-android"
	}

	req := RegisterRequest{
		Version:  105,
		NodeKey:  c.state.NodeKey().PublicHex("nodekey:"),
		Auth:     AuthBlock{AuthKey: c.state.AuthKey},
		Followup: followup,
		Hostinfo: &Hostinfo{
			OS:       "android",
			Hostname: hostname,
		},
	}

	reqBytes, err := json.Marshal(req)
	if err != nil {
		return "", false, err
	}

	client := &http.Client{
		Transport: &http2.Transport{
			AllowHTTP: true,
			DialTLSContext: func(ctx context.Context, network, addr string, cfg *tls.Config) (net.Conn, error) {
				return noiseConn, nil
			},
		},
	}

	registerURL := strings.TrimRight(c.controlURL, "/") + "/machine/register"
	httpReq, err := http.NewRequestWithContext(ctx, "POST", registerURL, bytes.NewReader(reqBytes))
	if err != nil {
		return "", false, err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(httpReq)
	if err != nil {
		return "", false, fmt.Errorf("register request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return "", false, fmt.Errorf("register HTTP %d: %s", resp.StatusCode, string(b))
	}

	var regResp RegisterResponse
	if err := json.NewDecoder(resp.Body).Decode(&regResp); err != nil {
		return "", false, fmt.Errorf("decode register response: %w", err)
	}

	if regResp.Error != "" {
		return "", false, errors.New(regResp.Error)
	}

	if regResp.MachineAuthorized {
		return "", true, nil
	}

	return regResp.AuthURL, false, nil
}

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

	mapReq := MapRequest{
		Version:   105,
		NodeKey:   c.state.NodeKey().PublicHex("nodekey:"),
		DiscoKey:  c.state.DiscoKey().PublicHex("discokey:"),
		Stream:    true,
		OmitPeers: false,
		Hostinfo: &Hostinfo{
			OS:       "android",
			Hostname: hostname,
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

// Close closes the active control session.
func (c *ControlClient) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.rawConn != nil {
		c.rawConn.Close()
		c.rawConn = nil
	}
	c.running = false
}
