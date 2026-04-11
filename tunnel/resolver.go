package tunnel

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/miekg/dns"
	"github.com/quic-go/quic-go"
)

// DNSProtocol represents the DNS transport protocol.
type DNSProtocol int

const (
	ProtocolPlain DNSProtocol = iota
	ProtocolDoH
	ProtocolDoT
	ProtocolDoQ
)

// ParseProtocol converts a string to DNSProtocol.
func ParseProtocol(s string) DNSProtocol {
	switch strings.ToUpper(s) {
	case "DOH":
		return ProtocolDoH
	case "DOT":
		return ProtocolDoT
	case "DOQ":
		return ProtocolDoQ
	default:
		return ProtocolPlain
	}
}

// Resolver handles DNS query forwarding across multiple protocols.
type Resolver struct {
	mu sync.RWMutex

	primaryServer   string
	fallbackServer  string
	dohURL          string
	protocol        DNSProtocol
	protectSocketFn func(fd int) bool

	// Split-DNS: route queries for specific zones to a different DNS server
	splitDNS   string   // DNS server for split zones (e.g., WireGuard DNS)
	splitZones []string // Zone suffixes (e.g., ["internal", "local", "lan"])

	// HTTP client for DoH (reusable)
	httpClient *http.Client
	// QUIC connection for DoQ (reusable)
	quicConn   quic.Connection
	quicMu     sync.Mutex
	quicServer string
}

const (
	queryTimeoutPlain = 5 * time.Second
	queryTimeoutDoH   = 5 * time.Second
	queryTimeoutDoT   = 5 * time.Second
	queryTimeoutDoQ   = 5 * time.Second
	connectTimeout    = 3 * time.Second
)

// NewResolver creates a new DNS resolver.
func NewResolver(protectFn func(fd int) bool) *Resolver {
	return &Resolver{
		protectSocketFn: protectFn,
		httpClient:      buildDoHClient(protectFn),
	}
}

// buildDoHClient creates an HTTP client with protected sockets for DoH.
func buildDoHClient(protectFn func(fd int) bool) *http.Client {
	dialer := &protectedDialer{protectFn: protectFn}
	transport := &http.Transport{
		DialContext:         dialer.DialContext,
		ForceAttemptHTTP2:   true,
		MaxIdleConns:        5,
		MaxIdleConnsPerHost: 2,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: connectTimeout,
	}
	return &http.Client{
		Transport: transport,
		Timeout:   queryTimeoutDoH,
	}
}

// Configure updates the resolver's DNS settings.
func (r *Resolver) Configure(protocol DNSProtocol, primary, fallback, dohURL string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.protocol = protocol
	r.primaryServer = primary
	r.fallbackServer = fallback
	r.dohURL = dohURL

	// Reset DoQ connection if server changed
	r.quicMu.Lock()
	if r.quicConn != nil && r.quicServer != dohURL {
		r.quicConn.CloseWithError(quic.ApplicationErrorCode(0), "config change")
		r.quicConn = nil
	}
	r.quicMu.Unlock()
}

// SetSplitDNS configures split-DNS routing for specific zones.
// Queries matching a zone suffix are resolved via the given DNS server
// using plain UDP WITHOUT socket protection (so they traverse the VPN/WireGuard tunnel).
func (r *Resolver) SetSplitDNS(dnsServer string, zones []string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.splitDNS = dnsServer
	r.splitZones = zones
	if dnsServer != "" && len(zones) > 0 {
		logf("Split-DNS configured: server=%s, zones=%v", dnsServer, zones)
	}
}

// matchesSplitZone checks if a domain matches any configured split-DNS zone.
func (r *Resolver) matchesSplitZone(domain string) bool {
	for _, zone := range r.splitZones {
		if domain == zone || strings.HasSuffix(domain, "."+zone) {
			return true
		}
	}
	return false
}

// extractDomain parses the queried domain name from a raw DNS query.
func extractDomain(rawQuery []byte) string {
	var msg dns.Msg
	if err := msg.Unpack(rawQuery); err != nil || len(msg.Question) == 0 {
		return ""
	}
	return strings.TrimSuffix(strings.ToLower(msg.Question[0].Name), ".")
}

// queryPlainUnprotected sends a DNS query via plain UDP WITHOUT socket protection.
// Used for split-DNS queries that must traverse the VPN/WireGuard tunnel.
func (r *Resolver) queryPlainUnprotected(rawQuery []byte, server string) ([]byte, error) {
	if !strings.Contains(server, ":") {
		server = server + ":53"
	}

	conn, err := net.DialTimeout("udp", server, connectTimeout)
	if err != nil {
		return nil, fmt.Errorf("split-dns dial: %w", err)
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(queryTimeoutPlain))

	if _, err := conn.Write(rawQuery); err != nil {
		return nil, fmt.Errorf("split-dns write: %w", err)
	}

	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, fmt.Errorf("split-dns read: %w", err)
	}

	return buf[:n], nil
}

// Resolve forwards a DNS query and returns the response.
// Split-DNS is handled by the engine (handleSplitDNSForward) before calling this.
func (r *Resolver) Resolve(rawQuery []byte) ([]byte, error) {
	r.mu.RLock()
	protocol := r.protocol
	primary := r.primaryServer
	fallback := r.fallbackServer
	dohURL := r.dohURL
	r.mu.RUnlock()

	// Try primary
	resp, err := r.query(rawQuery, protocol, primary, dohURL)
	if err == nil {
		return resp, nil
	}

	// Try fallback with PLAIN protocol if configured and different
	if fallback != "" && fallback != primary {
		resp, err2 := r.query(rawQuery, ProtocolPlain, fallback, "")
		if err2 == nil {
			return resp, nil
		}
		return nil, fmt.Errorf("primary (%s): %w; fallback (%s): %v", primary, err, fallback, err2)
	}

	return nil, err
}

// query performs a DNS query using the specified protocol.
func (r *Resolver) query(rawQuery []byte, protocol DNSProtocol, server, dohURL string) ([]byte, error) {
	switch protocol {
	case ProtocolDoH:
		return r.queryDoH(rawQuery, dohURL)
	case ProtocolDoT:
		return r.queryDoT(rawQuery, server)
	case ProtocolDoQ:
		return r.queryDoQ(rawQuery, dohURL)
	default:
		return r.queryPlain(rawQuery, server)
	}
}

// queryPlain sends a DNS query via plain UDP.
func (r *Resolver) queryPlain(rawQuery []byte, server string) ([]byte, error) {
	if !strings.Contains(server, ":") {
		server = server + ":53"
	}

	conn, err := net.DialTimeout("udp", server, connectTimeout)
	if err != nil {
		return nil, fmt.Errorf("plain dial: %w", err)
	}
	defer conn.Close()

	// Protect the socket from VPN routing loop
	if r.protectSocketFn != nil {
		if udpConn, ok := conn.(*net.UDPConn); ok {
			rawConn, err := udpConn.SyscallConn()
			if err == nil {
				rawConn.Control(func(fd uintptr) {
					r.protectSocketFn(int(fd))
				})
			}
		}
	}

	conn.SetDeadline(time.Now().Add(queryTimeoutPlain))

	if _, err := conn.Write(rawQuery); err != nil {
		return nil, fmt.Errorf("plain write: %w", err)
	}

	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, fmt.Errorf("plain read: %w", err)
	}

	return buf[:n], nil
}

// queryDoH sends a DNS query via DNS-over-HTTPS (RFC 8484 POST).
func (r *Resolver) queryDoH(rawQuery []byte, dohURL string) ([]byte, error) {
	if dohURL == "" {
		return nil, fmt.Errorf("DoH URL not configured")
	}

	var resp *http.Response
	var err error

	// Retry loop for HTTP/2 unexpected EOF (common with DoH load balancers)
	for attempt := 1; attempt <= 2; attempt++ {
		req, reqErr := http.NewRequest("POST", dohURL, bytes.NewReader(rawQuery))
		if reqErr != nil {
			return nil, fmt.Errorf("DoH request: %w", reqErr)
		}
		req.Header.Set("Content-Type", "application/dns-message")
		req.Header.Set("Accept", "application/dns-message")

		ctx, cancel := context.WithTimeout(context.Background(), queryTimeoutDoH)
		req = req.WithContext(ctx)

		resp, err = r.httpClient.Do(req)
		if err == nil {
			cancel()
			break
		}

		errStr := err.Error()
		if strings.Contains(errStr, "EOF") && attempt == 1 {
			cancel()
			time.Sleep(10 * time.Millisecond) // Small backoff before retry
			continue
		}
		cancel()
		return nil, fmt.Errorf("DoH request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == 403 {
		return nil, fmt.Errorf("DoH rate limited (403)")
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("DoH status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return nil, fmt.Errorf("DoH read: %w", err)
	}

	return body, nil
}

// queryDoT sends a DNS query via DNS-over-TLS (RFC 7858).
func (r *Resolver) queryDoT(rawQuery []byte, server string) ([]byte, error) {
	host := server
	port := "853"
	if h, p, err := net.SplitHostPort(server); err == nil {
		host = h
		port = p
	}

	dialer := &net.Dialer{Timeout: connectTimeout}

	// Resolve hostname first to protect the TCP socket
	ips, err := net.LookupHost(host)
	if err != nil || len(ips) == 0 {
		return nil, fmt.Errorf("DoT resolve %s: %w", host, err)
	}

	conn, err := dialer.Dial("tcp", net.JoinHostPort(ips[0], port))
	if err != nil {
		return nil, fmt.Errorf("DoT dial: %w", err)
	}

	// Protect the socket
	if r.protectSocketFn != nil {
		if tcpConn, ok := conn.(*net.TCPConn); ok {
			rawConn, err := tcpConn.SyscallConn()
			if err == nil {
				rawConn.Control(func(fd uintptr) {
					r.protectSocketFn(int(fd))
				})
			}
		}
	}

	tlsConn := tls.Client(conn, &tls.Config{
		ServerName: host,
		MinVersion: tls.VersionTLS12,
	})
	defer tlsConn.Close()

	tlsConn.SetDeadline(time.Now().Add(queryTimeoutDoT))

	if err := tlsConn.Handshake(); err != nil {
		return nil, fmt.Errorf("DoT TLS handshake: %w", err)
	}

	// DNS over TCP: 2-byte length prefix
	lenBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBuf, uint16(len(rawQuery)))
	if _, err := tlsConn.Write(append(lenBuf, rawQuery...)); err != nil {
		return nil, fmt.Errorf("DoT write: %w", err)
	}

	// Read response length
	if _, err := io.ReadFull(tlsConn, lenBuf); err != nil {
		return nil, fmt.Errorf("DoT read length: %w", err)
	}
	respLen := binary.BigEndian.Uint16(lenBuf)
	if respLen == 0 || respLen > 4096 {
		return nil, fmt.Errorf("DoT invalid response length: %d", respLen)
	}

	respBuf := make([]byte, respLen)
	if _, err := io.ReadFull(tlsConn, respBuf); err != nil {
		return nil, fmt.Errorf("DoT read response: %w", err)
	}

	return respBuf, nil
}

// queryDoQ sends a DNS query via DNS-over-QUIC (RFC 9250).
func (r *Resolver) queryDoQ(rawQuery []byte, doqURL string) ([]byte, error) {
	host, port := parseDoQURL(doqURL)
	if host == "" {
		return nil, fmt.Errorf("invalid DoQ URL: %s", doqURL)
	}

	conn, err := r.getOrCreateQUICConn(host, port)
	if err != nil {
		return nil, fmt.Errorf("DoQ connection: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), queryTimeoutDoQ)
	defer cancel()

	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		// Connection may be stale, reset and retry
		r.resetQUICConn()
		conn, err = r.getOrCreateQUICConn(host, port)
		if err != nil {
			return nil, fmt.Errorf("DoQ reconnect: %w", err)
		}
		stream, err = conn.OpenStreamSync(ctx)
		if err != nil {
			return nil, fmt.Errorf("DoQ stream: %w", err)
		}
	}

	// RFC 9250: 2-byte length prefix + DNS message
	lenBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBuf, uint16(len(rawQuery)))
	if _, err := stream.Write(append(lenBuf, rawQuery...)); err != nil {
		return nil, fmt.Errorf("DoQ write: %w", err)
	}
	stream.Close() // Signal end of data

	// Read response
	respData, err := io.ReadAll(io.LimitReader(stream, 65535))
	if err != nil {
		return nil, fmt.Errorf("DoQ read: %w", err)
	}

	// RFC 9250: response may have 2-byte length prefix
	if len(respData) >= 2 {
		respLen := binary.BigEndian.Uint16(respData[:2])
		if int(respLen) == len(respData)-2 {
			return respData[2:], nil
		}
	}

	return respData, nil
}

// getOrCreateQUICConn returns existing QUIC connection or creates a new one.
func (r *Resolver) getOrCreateQUICConn(host, port string) (quic.Connection, error) {
	r.quicMu.Lock()
	defer r.quicMu.Unlock()

	addr := net.JoinHostPort(host, port)
	if r.quicConn != nil && r.quicServer == addr {
		return r.quicConn, nil
	}

	// Resolve hostname
	ips, err := net.LookupHost(host)
	if err != nil || len(ips) == 0 {
		return nil, fmt.Errorf("DoQ resolve %s: %w", host, err)
	}

	udpAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(ips[0], port))
	if err != nil {
		return nil, fmt.Errorf("DoQ resolve UDP: %w", err)
	}

	udpConn, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, fmt.Errorf("DoQ listen: %w", err)
	}

	// Protect the UDP socket
	if r.protectSocketFn != nil {
		rawConn, err := udpConn.SyscallConn()
		if err == nil {
			rawConn.Control(func(fd uintptr) {
				r.protectSocketFn(int(fd))
			})
		}
	}

	tlsConf := &tls.Config{
		ServerName: host,
		NextProtos: []string{"doq"},
		MinVersion: tls.VersionTLS13,
	}

	ctx, cancel := context.WithTimeout(context.Background(), connectTimeout)
	defer cancel()

	transport := &quic.Transport{Conn: udpConn}
	conn, err := transport.Dial(ctx, udpAddr, tlsConf, &quic.Config{
		MaxIdleTimeout: 30 * time.Second,
	})
	if err != nil {
		udpConn.Close()
		return nil, fmt.Errorf("DoQ dial: %w", err)
	}

	r.quicConn = conn
	r.quicServer = addr
	return conn, nil
}

// resetQUICConn closes and clears the QUIC connection.
func (r *Resolver) resetQUICConn() {
	r.quicMu.Lock()
	defer r.quicMu.Unlock()

	if r.quicConn != nil {
		r.quicConn.CloseWithError(quic.ApplicationErrorCode(0), "reset")
		r.quicConn = nil
	}
}

// Shutdown cleans up resolver resources.
func (r *Resolver) Shutdown() {
	r.resetQUICConn()
	r.httpClient.CloseIdleConnections()
}

// ResolveARecord resolves a domain's A record via a protected plain DNS query.
// Used for SafeSearch IP resolution.
func (r *Resolver) ResolveARecord(domain, dnsServer string) (net.IP, error) {
	msg := new(dns.Msg)
	msg.SetQuestion(dns.Fqdn(domain), dns.TypeA)
	msg.RecursionDesired = true

	rawQuery, err := msg.Pack()
	if err != nil {
		return nil, fmt.Errorf("pack query: %w", err)
	}

	resp, err := r.queryPlain(rawQuery, dnsServer)
	if err != nil {
		return nil, err
	}

	var respMsg dns.Msg
	if err := respMsg.Unpack(resp); err != nil {
		return nil, fmt.Errorf("unpack response: %w", err)
	}

	for _, rr := range respMsg.Answer {
		if a, ok := rr.(*dns.A); ok {
			return a.A.To4(), nil
		}
	}

	return nil, fmt.Errorf("no A record for %s", domain)
}

// parseDoQURL parses a DoQ URL into hostname and port.
func parseDoQURL(url string) (host, port string) {
	// Remove scheme
	s := url
	for _, prefix := range []string{"quic://", "https://", "doq://"} {
		s = strings.TrimPrefix(s, prefix)
	}

	// Remove path
	if idx := strings.IndexByte(s, '/'); idx >= 0 {
		s = s[:idx]
	}

	// Split host:port
	host, port, err := net.SplitHostPort(s)
	if err != nil {
		// No port specified
		return s, "853"
	}
	return host, port
}

// protectedDialer wraps net.Dialer to protect sockets from VPN routing loop.
type protectedDialer struct {
	protectFn func(fd int) bool
}

func (d *protectedDialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: connectTimeout}
	conn, err := dialer.DialContext(ctx, network, addr)
	if err != nil {
		return nil, err
	}

	// Protect the socket
	if d.protectFn != nil {
		var rawConn interface{ Control(func(fd uintptr)) error }
		switch c := conn.(type) {
		case *net.TCPConn:
			rawConn, _ = c.SyscallConn()
		case *net.UDPConn:
			rawConn, _ = c.SyscallConn()
		}
		if rawConn != nil {
			rawConn.Control(func(fd uintptr) {
				d.protectFn(int(fd))
			})
		}
	}

	return conn, nil
}
