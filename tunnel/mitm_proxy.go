package tunnel

import (
	"bufio"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// AdBlockChecker is the interface the MITM proxy uses to query the ad-block
// engine. It mirrors the Engine's own blocking logic (Bloom → Trie → Custom
// Rules) so the proxy can reject ad domains at the TCP level before ever
// dialling the upstream server.
type AdBlockChecker interface {
	// IsDomainBlocked returns true if the given hostname (no port) should be
	// blocked according to the ad/security filter lists + custom rules.
	IsDomainBlocked(host string) bool
}

// ─────────────────────────────────────────────────────────────────────────────
// MITM Proxy — Smart HTTPS proxy for popup blocking.
//
// Runs on 127.0.0.1:8080. The Kotlin VpnService routes browser traffic here.
//
// Decision flow for CONNECT requests:
//   1. MitmFilter.IsInterceptionAllowed(host)?
//      NO  → ForwardDirect (raw TCP tunnel, no decryption)
//      YES → Attempt MITM TLS handshake
//              ├── Handshake OK   → Inject popup-killer into HTML responses
//              └── Handshake FAIL → Auto-blacklist domain, forward direct
//
// Resource management:
//   • 5s dial timeout (fast fail for unreachable servers)
//   • 30s idle timeout
//   • 3min max connection lifetime
//   • sync.WaitGroup for clean shutdown
// ─────────────────────────────────────────────────────────────────────────────

const (
	dialTimeout     = 5 * time.Second  // Short! Fail fast on Android.
	idleTimeout     = 30 * time.Second
	maxConnLifetime = 3 * time.Minute
	proxyReadBuf    = 32 * 1024 // 32KB read buffer
)

// MitmProxy is the local HTTPS MITM proxy server.
type MitmProxy struct {
	mu       sync.Mutex
	listener net.Listener
	certMgr  *CertManager
	filter   *MitmFilter
	blocker  AdBlockChecker // nil = no ad-blocking at proxy level
	running  bool
	wg       sync.WaitGroup

	// Stats
	proxyBlocked atomic.Int64
}

// NewMitmProxy creates a new MITM proxy with a persistent Root CA.
// certDir is the directory where ca.crt and ca.key are stored.
func NewMitmProxy(certDir string) (*MitmProxy, error) {
	cm, err := NewCertManager(certDir)
	if err != nil {
		return nil, fmt.Errorf("init cert manager: %w", err)
	}
	// Pre-generate TLS cert for the local asset server so the first
	// request doesn't incur cert-generation latency.
	cm.WarmLocalAssetCert()

	return &MitmProxy{
		certMgr: cm,
		filter:  NewMitmFilter(),
	}, nil
}

// GetCACertPEM returns the PEM-encoded Root CA cert for user installation.
func (p *MitmProxy) GetCACertPEM() string {
	return p.certMgr.GetCACertPEM()
}

// GetFilter returns the MitmFilter for UID configuration.
func (p *MitmProxy) GetFilter() *MitmFilter {
	return p.filter
}

// SetAdBlockChecker injects the ad-block engine so the proxy can reject
// blocked domains at the TCP level (before dialling upstream).
func (p *MitmProxy) SetAdBlockChecker(checker AdBlockChecker) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.blocker = checker
}

// GetProxyBlockedCount returns how many requests the proxy rejected via the
// ad-block gate (useful for stats / debugging).
func (p *MitmProxy) GetProxyBlockedCount() int64 {
	return p.proxyBlocked.Load()
}

// Listen binds the TCP listener synchronously. Call this BEFORE configuring
// HTTP proxy routing in the VPN so the port is guaranteed to be open.
func (p *MitmProxy) Listen(addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("listen %s: %w", addr, err)
	}

	p.mu.Lock()
	p.listener = ln
	p.running = true
	p.mu.Unlock()

	logf("MITM Proxy: listening on %s", addr)
	return nil
}

// Serve runs the accept loop. This function blocks; call it in a goroutine
// AFTER Listen() has returned successfully.
func (p *MitmProxy) Serve() error {
	p.mu.Lock()
	ln := p.listener
	p.mu.Unlock()

	if ln == nil {
		return fmt.Errorf("Serve called before Listen")
	}

	for {
		conn, err := ln.Accept()
		if err != nil {
			p.mu.Lock()
			running := p.running
			p.mu.Unlock()
			if !running {
				return nil // Clean shutdown
			}
			logf("MITM Proxy: accept error: %v", err)
			continue
		}

		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			p.handleConnection(conn)
		}()
	}
}

// Stop gracefully shuts down the proxy.
func (p *MitmProxy) Stop() {
	p.mu.Lock()
	p.running = false
	if p.listener != nil {
		p.listener.Close()
	}
	p.mu.Unlock()

	// Wait for active connections to finish (with timeout)
	done := make(chan struct{})
	go func() {
		p.wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		logf("MITM Proxy: stopped gracefully (auto-blacklisted %d domains)", p.filter.GetBlacklistCount())
	case <-time.After(10 * time.Second):
		logf("MITM Proxy: force-stopped (timeout)")
	}
	logf("MITM Proxy: blocked %d ad requests at proxy level", p.proxyBlocked.Load())
}

// ── Connection Handling ──────────────────────────────────────────────────────

func (p *MitmProxy) handleConnection(clientConn net.Conn) {
	defer clientConn.Close()

	// Set max lifetime on the client connection
	clientConn.SetDeadline(time.Now().Add(maxConnLifetime))

	// Set a short read deadline for the initial HTTP header parsing.
	// This prevents goroutine leaks from stalled/slow connections (Slowloris).
	clientConn.SetReadDeadline(time.Now().Add(10 * time.Second))

	// Read the HTTP request
	reader := bufio.NewReader(clientConn)
	req, err := http.ReadRequest(reader)
	if err != nil {
		return // Invalid or timed-out request, just close
	}

	// Reset the deadline back to the full connection lifetime
	clientConn.SetReadDeadline(time.Time{})

	if req.Method == http.MethodConnect {
		p.handleConnect(clientConn, req)
	} else {
		p.handleHTTP(clientConn, req)
	}
}

// handleConnect processes HTTPS CONNECT requests.
// 3-tier decision flow:
//   Gate 1 — BLOCK:        Domain is in ad/security filter lists → 403 immediately.
//   Gate 2 — PASS-THROUGH: Domain is sensitive (banking, cert-pinned) → raw TCP tunnel.
//   Gate 3 — MITM:         Domain is safe to intercept → decrypt + inject scripts/CSS.
func (p *MitmProxy) handleConnect(clientConn net.Conn, req *http.Request) {
	host := req.Host

	// Normalize host (add default port if missing)
	if !strings.Contains(host, ":") {
		host = host + ":443"
	}

	hostname := hostOnly(host)

	// ── Guard: Block loopback/internal addresses to prevent SSRF ──────
	if isLoopbackOrInternal(hostname) {
		writeHTTPError(clientConn, 403, "Blocked: loopback address")
		return
	}

	// ── Gate 1: BLOCK — ad/tracker domain? Drop immediately. ──────────
	// NOTE: Ad blocking applies to ALL apps, not just browsers.
	if p.blocker != nil && p.blocker.IsDomainBlocked(hostname) {
		p.proxyBlocked.Add(1)
		writeHTTPError(clientConn, 403, "Blocked by BlockAds")
		return
	}

	// ── Gate 2: UID CHECK — only intercept selected browsers ──────────
	// Resolve the source UID from /proc/net/tcp to determine which app
	// initiated this connection. Non-browser apps get pass-through.
	sourceUID := resolveConnUID(clientConn)
	if sourceUID >= 0 && !p.filter.IsUIDAllowed(sourceUID) {
		// Not a selected browser → tunnel directly, no MITM
		p.forwardDirect(clientConn, host)
		return
	}

	// ── Gate 3: PASS-THROUGH — sensitive / cert-pinned domain ─────────
	if !p.filter.IsInterceptionAllowed(hostname) {
		// Direct tunnel — no decryption
		p.forwardDirect(clientConn, host)
		return
	}

	// ── Gate 3a: LOCAL ASSET SERVER — serve from memory ───────────────
	if IsLocalAssetHost(hostname) {
		p.serveLocalAssetDirect(clientConn, hostname)
		return
	}

	// ── Gate 3b: MITM — decrypt + inject popup-killer + cosmetic CSS ──
	p.mitmIntercept(clientConn, host, hostname)
}

// forwardDirect creates a raw TCP tunnel (no TLS interception).
func (p *MitmProxy) forwardDirect(clientConn net.Conn, host string) {
	// Connect to the real server
	serverConn, err := net.DialTimeout("tcp", host, dialTimeout)
	if err != nil {
		writeHTTPError(clientConn, 502, "Bad Gateway")
		return
	}
	defer serverConn.Close()

	// Send 200 OK to tell the client the tunnel is established
	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	// Bidirectional copy with timeout management
	serverConn.SetDeadline(time.Now().Add(maxConnLifetime))
	bidirectionalCopy(clientConn, serverConn)
}

// mitmIntercept performs TLS interception with handshake fail-safe.
// If the client TLS handshake fails (cert pinning), the domain is
// automatically blacklisted for all future requests.
func (p *MitmProxy) mitmIntercept(clientConn net.Conn, host, hostname string) {
	// Connect to the real server first (verify it's reachable)
	serverConn, err := tls.DialWithDialer(
		&net.Dialer{Timeout: dialTimeout},
		"tcp", host,
		&tls.Config{
			ServerName:         hostname,
			InsecureSkipVerify: false,
		},
	)
	if err != nil {
		// Real server unreachable or invalid cert → fall back to direct
		logf("MITM: server TLS dial to %s failed, falling back to direct: %v", host, err)
		p.forwardDirect(clientConn, host)
		return
	}
	defer serverConn.Close()
	serverConn.SetDeadline(time.Now().Add(maxConnLifetime))

	// Tell the client the tunnel is established
	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	// Get the shared dynamic TLS config (handles SNI & caching & singleflight internally)
	tlsConfig := p.certMgr.GetDynamicTLSConfigForHost(hostname)

	// ── Handshake Fail-Safe ──────────────────────────────────────────
	// TLS handshake with the client using our dynamic cert.
	// If the client rejects it (cert pinning), we auto-blacklist this domain.
	clientTLS := tls.Server(clientConn, tlsConfig)
	if err := clientTLS.Handshake(); err != nil {
		// *** CERT PINNING DETECTED ***
		// The client app rejected our CA-signed cert.
		// Auto-blacklist this domain so future requests go direct.
		errStr := err.Error()
		if strings.Contains(errStr, "unknown certificate") ||
			strings.Contains(errStr, "handshake failure") ||
			strings.Contains(errStr, "certificate unknown") ||
			strings.Contains(errStr, "bad certificate") ||
			strings.Contains(errStr, "tls:") {
			p.filter.BlacklistDomain(hostname)
		}
		logf("MITM: client handshake failed for %s (auto-blacklisted): %v", hostname, err)
		return
	}
	defer clientTLS.Close()

	// Handshake succeeded! Relay HTTP with popup injection.
	p.relayHTTP(clientTLS, serverConn, hostname)
}

// serveLocalAssetDirect handles CONNECT requests to local.pwhs.app.
// Instead of dialling an upstream server, it performs the TLS handshake
// with the client using a dynamically generated cert and then serves
// assets directly from memory.
func (p *MitmProxy) serveLocalAssetDirect(clientConn net.Conn, hostname string) {
	// Tell the client the tunnel is established
	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	// Get the shared dynamic TLS config (handles SNI & caching & singleflight internally)
	tlsConfig := p.certMgr.GetDynamicTLSConfigForHost(hostname)

	// TLS handshake with the client
	clientTLS := tls.Server(clientConn, tlsConfig)
	if err := clientTLS.Handshake(); err != nil {
		logf("Local asset server: TLS handshake failed: %v", err)
		return
	}
	defer clientTLS.Close()

	// Serve HTTP requests from memory (no upstream connection needed)
	clientReader := bufio.NewReader(clientTLS)
	for {
		req, err := http.ReadRequest(clientReader)
		if err != nil {
			return // Client closed or error
		}

		resp := ServeLocalAsset(req)
		if err := resp.Write(clientTLS); err != nil {
			return
		}

		if req.Close {
			return
		}
	}
}

// relayHTTP relays HTTP request/response pairs between the MITM'd client
// and the real server, injecting the popup-killer into HTML responses.
// Also applies Gate 1 (ad-blocking) to individual sub-requests within the
// MITM'd TLS session (e.g., tracker scripts loaded as sub-resources).
func (p *MitmProxy) relayHTTP(clientConn net.Conn, serverConn net.Conn, hostname string) {
	clientReader := bufio.NewReader(clientConn)
	serverReader := bufio.NewReader(serverConn)

	for {
		// Read request from client
		req, err := http.ReadRequest(clientReader)
		if err != nil {
			return // Client closed or error
		}

		// Set Host header if missing
		if req.Host == "" {
			req.Host = hostname
		}

		// ── Sub-request Block Gate ────────────────────────────────────
		// Even inside an MITM'd connection, individual sub-requests may
		// target ad/tracker domains (via Host header). Block them here.
		reqHost := hostOnly(req.Host)

		// ── Local Asset Server sub-request ──────────────────────────
		// If the browser requests local.pwhs.app resources inside
		// an existing MITM'd connection, serve from memory inline.
		if IsLocalAssetHost(reqHost) {
			resp := ServeLocalAsset(req)
			resp.Write(clientConn)
			continue
		}

		if p.blocker != nil && reqHost != hostname && p.blocker.IsDomainBlocked(reqHost) {
			p.proxyBlocked.Add(1)
			// Write a minimal HTTP 403 response inline (don't close the connection)
			blockedResp := &http.Response{
				StatusCode: 403,
				ProtoMajor: 1,
				ProtoMinor: 1,
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader("Blocked by BlockAds")),
			}
			blockedResp.Header.Set("Connection", "keep-alive")
			blockedResp.Header.Set("Content-Length", "19")
			blockedResp.Write(clientConn)
			continue
		}

		// Forward request to real server (strip Accept-Encoding to get
		// uncompressed responses — otherwise we can't inject into gzip)
		req.Header.Del("Accept-Encoding")

		if err := req.Write(serverConn); err != nil {
			return
		}

		// Read response from server
		resp, err := http.ReadResponse(serverReader, req)
		if err != nil {
			return
		}

		// Check if this is an HTML response that should be injected
		contentType := resp.Header.Get("Content-Type")
		if ShouldInjectHTML(contentType) {
			// Wrap the body with our injecting reader
			originalBody := resp.Body
			resp.Body = io.NopCloser(NewInjectingReader(originalBody))

			// Remove Content-Length since injection changes the size.
			// This forces chunked transfer encoding.
			resp.ContentLength = -1
			resp.Header.Del("Content-Length")
			resp.Header.Del("Content-Encoding")
			resp.Header.Del("Transfer-Encoding")
		}

		// Write response to client
		if err := resp.Write(clientConn); err != nil {
			resp.Body.Close()
			return
		}
		resp.Body.Close()

		// Check if the connection should be closed
		if resp.Close || req.Close {
			return
		}
	}
}

// handleHTTP handles plain HTTP requests (non-CONNECT).
func (p *MitmProxy) handleHTTP(clientConn net.Conn, req *http.Request) {
	host := req.Host
	if !strings.Contains(host, ":") {
		host = host + ":80"
	}

	hostname := hostOnly(host)

	// ── Gate 1: BLOCK — ad/tracker domain? Drop immediately. ──────────
	if p.blocker != nil && p.blocker.IsDomainBlocked(hostname) {
		p.proxyBlocked.Add(1)
		writeHTTPError(clientConn, 403, "Blocked by BlockAds")
		return
	}

	serverConn, err := net.DialTimeout("tcp", host, dialTimeout)
	if err != nil {
		writeHTTPError(clientConn, 502, "Bad Gateway")
		return
	}
	defer serverConn.Close()
	serverConn.SetDeadline(time.Now().Add(maxConnLifetime))

	// Strip Accept-Encoding for injection
	req.Header.Del("Accept-Encoding")

	if err := req.Write(serverConn); err != nil {
		return
	}

	serverReader := bufio.NewReader(serverConn)
	resp, err := http.ReadResponse(serverReader, req)
	if err != nil {
		return
	}

	contentType := resp.Header.Get("Content-Type")
	if ShouldInjectHTML(contentType) {
		originalBody := resp.Body
		resp.Body = io.NopCloser(NewInjectingReader(originalBody))
		resp.ContentLength = -1
		resp.Header.Del("Content-Length")
		resp.Header.Del("Content-Encoding")
	}

	resp.Write(clientConn)
	resp.Body.Close()
}

// ── Utilities ────────────────────────────────────────────────────────────────

// bidirectionalCopy copies data between two connections in both directions.
func bidirectionalCopy(a, b net.Conn) {
	done := make(chan struct{}, 2)

	go func() {
		io.Copy(b, a)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(a, b)
		done <- struct{}{}
	}()

	<-done
}

// writeHTTPError writes an HTTP error response.
func writeHTTPError(w net.Conn, code int, msg string) {
	resp := fmt.Sprintf("HTTP/1.1 %d %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", code, msg, len(msg), msg)
	w.Write([]byte(resp))
}

// hostOnly strips the port from a host:port string.
func hostOnly(hostport string) string {
	host, _, err := net.SplitHostPort(hostport)
	if err != nil {
		return hostport
	}
	return host
}

// isLoopbackOrInternal returns true if the hostname resolves to a loopback
// or private/internal IP address. This prevents the proxy from connecting
// back to itself (SSRF infinite loop) or reaching internal network services.
func isLoopbackOrInternal(hostname string) bool {
	// Fast-path: check common literal names
	lower := strings.ToLower(hostname)
	if lower == "localhost" || lower == "0.0.0.0" || lower == "::" {
		return true
	}

	ip := net.ParseIP(hostname)
	if ip == nil {
		// Not a literal IP — resolve it
		addrs, err := net.LookupHost(hostname)
		if err != nil || len(addrs) == 0 {
			return false
		}
		ip = net.ParseIP(addrs[0])
		if ip == nil {
			return false
		}
	}

	return ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || isPrivateIP(ip)
}

// isPrivateIP checks if an IP is in RFC 1918 private ranges.
func isPrivateIP(ip net.IP) bool {
	privateRanges := []struct {
		network string
	}{
		{"10.0.0.0/8"},
		{"172.16.0.0/12"},
		{"192.168.0.0/16"},
	}
	for _, r := range privateRanges {
		_, cidr, _ := net.ParseCIDR(r.network)
		if cidr.Contains(ip) {
			return true
		}
	}
	return false
}
