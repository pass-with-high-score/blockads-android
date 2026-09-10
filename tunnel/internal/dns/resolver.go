package dns

import (
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	miekgdns "github.com/miekg/dns"
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
	splitDNS   string
	splitZones []string

	// HTTP client for DoH (reusable)
	httpClient *http.Client
	// QUIC connection for DoQ (reusable)
	quicMu     sync.Mutex
	quicConn   quic.Connection
	quicServer string
}

const (
	connectTimeout  = 5 * time.Second
	queryTimeoutUDP = 4 * time.Second
	queryTimeoutDoH = 5 * time.Second
	queryTimeoutDoT = 5 * time.Second
	queryTimeoutDoQ = 5 * time.Second
)

// NewResolver creates a new DNS resolver.
func NewResolver(protectFn func(fd int) bool) *Resolver {
	r := &Resolver{
		protocol:        ProtocolPlain,
		primaryServer:   "9.9.9.9",
		fallbackServer:  "94.140.14.14",
		protectSocketFn: protectFn,
	}

	dialer := &protectedDialer{protectFn: protectFn}
	transport := &http.Transport{
		DialContext:         dialer.DialContext,
		MaxIdleConns:        10,
		IdleConnTimeout:     30 * time.Second,
		TLSHandshakeTimeout: connectTimeout,
		ForceAttemptHTTP2:   true,
	}
	r.httpClient = &http.Client{
		Transport: transport,
		Timeout:   queryTimeoutDoH,
	}

	return r
}

// Configure updates the resolver configuration.
func (r *Resolver) Configure(protocol DNSProtocol, primary, fallback, dohURL string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.protocol = protocol
	if primary != "" {
		r.primaryServer = primary
	}
	if fallback != "" {
		r.fallbackServer = fallback
	}
	r.dohURL = dohURL

	if protocol != ProtocolDoQ {
		r.resetQUICConn()
	}
}

// SetSplitDNS configures split-DNS zones and their upstream DNS server.
func (r *Resolver) SetSplitDNS(dnsServer string, zones []string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.splitDNS = dnsServer
	r.splitZones = zones
}

// SplitDNS returns the configured split DNS server.
func (r *Resolver) SplitDNS() string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.splitDNS
}

// SplitZones returns a copy of the configured split DNS zones.
func (r *Resolver) SplitZones() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	zones := make([]string, len(r.splitZones))
	copy(zones, r.splitZones)
	return zones
}

// MatchesSplitZone checks if a domain matches any configured split-DNS zone.
func (r *Resolver) MatchesSplitZone(domain string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.matchesSplitZone(domain)
}

func (r *Resolver) matchesSplitZone(domain string) bool {
	clean := strings.TrimSuffix(strings.ToLower(domain), ".")
	for _, zone := range r.splitZones {
		z := strings.TrimSuffix(strings.ToLower(zone), ".")
		if clean == z || strings.HasSuffix(clean, "."+z) {
			return true
		}
	}
	return false
}

// queryPlainUnprotected sends a plain UDP query without socket protection.
func (r *Resolver) queryPlainUnprotected(rawQuery []byte, server string) ([]byte, error) {
	host := server
	port := "53"
	if h, p, err := net.SplitHostPort(server); err == nil {
		host = h
		port = p
	}

	conn, err := net.DialTimeout("udp", net.JoinHostPort(host, port), connectTimeout)
	if err != nil {
		return nil, fmt.Errorf("unprotected dial: %w", err)
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(queryTimeoutUDP))

	if _, err := conn.Write(rawQuery); err != nil {
		return nil, fmt.Errorf("unprotected write: %w", err)
	}

	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, fmt.Errorf("unprotected read: %w", err)
	}

	return buf[:n], nil
}

// Resolve sends a raw DNS query to the configured upstream server.
func (r *Resolver) Resolve(rawQuery []byte) ([]byte, error) {
	r.mu.RLock()
	protocol := r.protocol
	primary := r.primaryServer
	fallback := r.fallbackServer
	dohURL := r.dohURL
	splitDNS := r.splitDNS
	hasSplitZones := len(r.splitZones) > 0
	r.mu.RUnlock()

	if hasSplitZones && splitDNS != "" {
		var msg miekgdns.Msg
		if err := msg.Unpack(rawQuery); err == nil && len(msg.Question) > 0 {
			domain := msg.Question[0].Name
			r.mu.RLock()
			isSplit := r.matchesSplitZone(domain)
			r.mu.RUnlock()
			if isSplit {
				return r.queryPlain(rawQuery, splitDNS)
			}
		}
	}

	resp, err := r.query(rawQuery, protocol, primary, dohURL)
	if err == nil {
		return resp, nil
	}

	if fallback != "" && fallback != primary {
		return r.query(rawQuery, ProtocolPlain, fallback, "")
	}

	return nil, err
}

// query executes a DNS query using the specified protocol.
func (r *Resolver) query(rawQuery []byte, protocol DNSProtocol, server, dohURL string) ([]byte, error) {
	switch protocol {
	case ProtocolDoH:
		return r.queryDoH(rawQuery, dohURL)
	case ProtocolDoT:
		return r.queryDoT(rawQuery, server)
	case ProtocolDoQ:
		return r.queryDoQ(rawQuery, server)
	default:
		return r.queryPlain(rawQuery, server)
	}
}

// queryPlain sends a plain UDP DNS query with socket protection.
func (r *Resolver) queryPlain(rawQuery []byte, server string) ([]byte, error) {
	host := server
	port := "53"
	if h, p, err := net.SplitHostPort(server); err == nil {
		host = h
		port = p
	}

	udpAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, port))
	if err != nil {
		return nil, fmt.Errorf("plain resolve: %w", err)
	}

	conn, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, fmt.Errorf("plain listen: %w", err)
	}
	defer conn.Close()

	if r.protectSocketFn != nil {
		rawConn, err := conn.SyscallConn()
		if err == nil {
			rawConn.Control(func(fd uintptr) {
				r.protectSocketFn(int(fd))
			})
		}
	}

	conn.SetDeadline(time.Now().Add(queryTimeoutUDP))

	if _, err := conn.WriteTo(rawQuery, udpAddr); err != nil {
		return nil, fmt.Errorf("plain write: %w", err)
	}

	buf := make([]byte, 4096)
	n, _, err := conn.ReadFrom(buf)
	if err != nil {
		return nil, fmt.Errorf("plain read: %w", err)
	}

	return buf[:n], nil
}

// Shutdown cleans up resolver resources.
func (r *Resolver) Shutdown() {
	r.resetQUICConn()
	r.httpClient.CloseIdleConnections()
}

// ResolveARecord resolves a domain's A record via a protected plain DNS query.
func (r *Resolver) ResolveARecord(domain, dnsServer string) (net.IP, error) {
	msg := new(miekgdns.Msg)
	msg.SetQuestion(miekgdns.Fqdn(domain), miekgdns.TypeA)
	msg.RecursionDesired = true

	rawQuery, err := msg.Pack()
	if err != nil {
		return nil, fmt.Errorf("pack query: %w", err)
	}

	resp, err := r.queryPlain(rawQuery, dnsServer)
	if err != nil {
		return nil, err
	}

	var respMsg miekgdns.Msg
	if err := respMsg.Unpack(resp); err != nil {
		return nil, fmt.Errorf("unpack response: %w", err)
	}

	for _, rr := range respMsg.Answer {
		if a, ok := rr.(*miekgdns.A); ok {
			return a.A.To4(), nil
		}
	}

	return nil, fmt.Errorf("no A record for %s", domain)
}
