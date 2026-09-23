// Package tunnel provides a Go-based DNS tunnel engine for Android ad blocking.
//
// This package is designed to be compiled with gomobile bind and used from
// Android Kotlin code. It handles TUN packet processing, DNS query forwarding
// (Plain/DoH/DoT/DoQ), domain blocking, SafeSearch enforcement, and
// YouTube restricted mode.
//
// The exported API uses only gomobile-compatible types (string, []byte, int, bool).
package tunnel

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/miekg/dns"
)

// LogCallback is the interface for receiving DNS query events in Kotlin.
// gomobile will generate the corresponding Java/Kotlin interface.
type LogCallback interface {
	// OnDNSQuery is called for each DNS query processed.
	OnDNSQuery(domain string, blocked bool, queryType int, responseTimeMs int64, appName string, resolvedIP string, blockedBy string)
}

// DomainChecker is the interface for checking if a domain should be blocked.
// The implementation lives in Kotlin (using efficient mmap'd Trie data structures)
// so we don't need to export 200k+ domains to Go.
type DomainChecker interface {
	// IsBlocked returns true if the domain should be blocked.
	IsBlocked(domain string) bool
	// GetBlockReason returns the reason a domain is blocked (e.g., "ad", "security", "custom").
	// Returns empty string if not blocked.
	GetBlockReason(domain string) string
	// HasCustomRule checks if a domain matches a custom allow or block rule.
	// Returns 1 for block override, 0 for allow override, -1 for no override.
	HasCustomRule(domain string) int
}

// FirewallChecker checks if a DNS query from a specific app should be blocked.
// The implementation lives in Kotlin and uses UID resolution + FirewallManager.
type FirewallChecker interface {
	// ShouldBlock checks if the app owning the DNS connection should be blocked.
	// sourcePort: the source UDP port of the DNS query
	// sourceIP: the source IP address bytes
	// destIP: the destination IP address bytes
	ShouldBlock(appName string) bool
}

// AppResolver interface to allow Kotlin to return the AppName for a connection
type AppResolver interface {
	ResolveApp(sourcePort int, sourceIP []byte, destIP []byte, destPort int) string
}

// AppUidResolver maps an Android UID → package name (Kotlin-side, via
// PackageManager.getPackagesForUid). The UID comes from
// getConnectionOwnerUid. Unlike AppResolver it takes only an int (no
// []byte), so it is safe to call from the concurrent full-tunnel flow hot
// path — passing Go []byte to the gomobile JNI there panics under Go's
// cgocheck ("Go pointer to unpinned Go pointer").
type AppUidResolver interface {
	PackageForUid(uid int) string
}

// SocketProtector is the interface for protecting sockets from VPN routing loop.
// Implemented in Kotlin via VpnService.protect().
type SocketProtector interface {
	// Protect protects a socket file descriptor from the VPN routing loop.
	Protect(fd int) bool
}

// Engine is the main DNS tunnel engine.
// All exported methods use gomobile-compatible types.
type Engine struct {
	protocol        string
	primaryDNS      string
	fallbackDNS     string
	dohURL          string
	responseType    ResponseType
	logCallback     LogCallback
	resolver        *Resolver
	safeSearch      *SafeSearch
	domainChecker   DomainChecker
	firewallChecker FirewallChecker
	appResolver     AppResolver
	appUidResolver  AppUidResolver

	adTries    []*MmapTrie
	adTrieIDs  []string
	secTries   []*MmapTrie
	secTrieIDs []string

	// Bloom filters for fast pre-filtering (skip trie if definitely clean)
	adBlooms  []*BloomFilter
	secBlooms []*BloomFilter

	mu         sync.Mutex
	running    bool
	tunFile    *os.File
	dohDomains map[string]struct{}

	// Pipeline components
	router      *Router
	interceptor *DnsInterceptor

	// Split-DNS zones (comma-separated, set from Kotlin)
	splitZones string

	// Userspace TCP/IP stack (AdGuard-style model — Phase E).
	//
	// tcpStackPipe uses atomic.Pointer because the DnsInterceptor hot
	// path reads it without holding e.mu — racing with Stop would be a
	// data race otherwise. The pipe's own Close is panic-free so a
	// stale pointer read + Push is safe (silently drops).
	tcpStack     *TcpIpStack
	tcpStackPipe atomic.Pointer[packetPipe]
	useTcpStack  atomic.Bool

	// connLogEnabled mirrors the app's "record logs" preference. Connection
	// logging resolves the owning app per flow, which costs two binder round
	// trips (getConnectionOwnerUid + getPackagesForUid) — far too expensive
	// to pay when the user isn't recording logs at all. Checked before any
	// resolution work; see logConnection.
	connLogEnabled atomic.Bool

	// quicDrop: when true, browser QUIC (UDP 443) is dropped to force
	// HTTP/3 traffic onto TCP TLS where the MITM can filter it. This gives
	// maximum in-page filtering coverage but makes some sites load
	// partially (browsers retry QUIC before falling back). When false
	// (default), QUIC is relayed so pages load fully/smoothly; DNS-level
	// ad-blocking still applies. Toggled from the UI via SetFilterHttp3.
	quicDrop atomic.Bool

	// Stack-mode MITM state (Phase D). When both are non-nil, the stack
	// uses the MITM TCP handler; otherwise the Phase C direct-dial
	// passthrough handler is used.
	stackCertMgr    *CertManager
	stackMitmFilter *MitmFilter
	certDir         string // persistent dir (for CA + goroutine-dump diagnostics)

	// UID resolver — supplied by Kotlin. When nil, flow-level UID lookup
	// falls back to UIDUnknown. Stored on the engine so both the stack
	// (once created) and any future consumer can pull from one place.
	uidResolver UIDResolver

	// protectFn is captured at Start time from the SocketProtector.
	// Handlers that dial outbound (direct flows, resolver fallbacks)
	// use it to ensure the socket bypasses the VPN.
	protectFn func(fd int) bool

	// fullTunnelDone is created by StartFull and closed by Stop to unblock
	// the full-network engine loop. Nil in the legacy DNS-only / WireGuard
	// modes (StartFull is a separate, isolated data path — see fulltunnel.go).
	fullTunnelDone chan struct{}

	// Standalone Servers
	standaloneUdp  *dns.Server
	standaloneTcp  *dns.Server
	standaloneUdp6 *dns.Server
	standaloneTcp6 *dns.Server

	// Stats
	totalQueries   atomic.Int64
	blockedQueries atomic.Int64
}

// Stats holds engine statistics.
type Stats struct {
	TotalQueries   int64 `json:"total"`
	BlockedQueries int64 `json:"blocked"`
}

// NewEngine creates a new Engine instance.
func NewEngine() *Engine {
	router := NewRouter()
	e := &Engine{
		safeSearch:   NewSafeSearch(),
		responseType: ResponseCustomIP,
		router:       router,
	}
	e.interceptor = NewDnsInterceptor(e, router)
	return e
}

// GetRouter returns the engine's Router for setting outbound adapters.
func (e *Engine) GetRouter() *Router {
	return e.router
}

// SetOutboundAdapter sets the active outbound adapter on the router.
// Pass nil to switch to DNS-only mode (no proxy).
func (e *Engine) SetOutboundAdapter(adapter OutboundAdapter) {
	e.router.SetAdapter(adapter)
}

// SetDomainChecker sets the Kotlin-side domain checker.
// This is called before Start() to provide the blocking logic for rules not in the trie (like Custom Rules).
func (e *Engine) SetDomainChecker(checker DomainChecker) {
	e.domainChecker = checker
}

// SetTries loads the native memory-mapped domain tries and bloom filters for blazing-fast lookups in Go.
// It accepts the comma-separated absolute paths to the ad/security binary trie files and their corresponding bloom filter files.
func (e *Engine) SetTries(adTriePathsCsv, secTriePathsCsv, adBloomPathsCsv, secBloomPathsCsv string) {
	e.mu.Lock()
	defer e.mu.Unlock()

	// Close old tries
	for _, t := range e.adTries {
		if t != nil {
			t.Close()
		}
	}
	e.adTries = nil
	e.adTrieIDs = nil

	for _, t := range e.secTries {
		if t != nil {
			t.Close()
		}
	}
	e.secTries = nil
	e.secTrieIDs = nil

	// Close old bloom filters
	for _, bf := range e.adBlooms {
		if bf != nil {
			bf.Close()
		}
	}
	e.adBlooms = nil

	for _, bf := range e.secBlooms {
		if bf != nil {
			bf.Close()
		}
	}
	e.secBlooms = nil

	// Load ad tries
	for _, path := range strings.Split(adTriePathsCsv, ",") {
		path = strings.TrimSpace(path)
		if path == "" {
			continue
		}
		t, err := LoadMmapTrie(path)
		if err != nil {
			logf("Failed to load Ad Trie from %s: %v", path, err)
		} else {
			e.adTries = append(e.adTries, t)
			id := strings.TrimSuffix(filepath.Base(path), ".trie")
			e.adTrieIDs = append(e.adTrieIDs, id)
			logf("Loaded Ad Trie from Go native Mmap: %s", path)
		}
	}

	// Load security tries
	for _, path := range strings.Split(secTriePathsCsv, ",") {
		path = strings.TrimSpace(path)
		if path == "" {
			continue
		}
		t, err := LoadMmapTrie(path)
		if err != nil {
			logf("Failed to load Security Trie from %s: %v", path, err)
		} else {
			e.secTries = append(e.secTries, t)
			id := strings.TrimSuffix(filepath.Base(path), ".trie")
			e.secTrieIDs = append(e.secTrieIDs, id)
			logf("Loaded Security Trie from Go native Mmap: %s", path)
		}
	}

	// Load ad bloom filter
	for _, path := range strings.Split(adBloomPathsCsv, ",") {
		path = strings.TrimSpace(path)
		if path == "" {
			continue
		}
		bf, err := LoadBloomFilter(path)
		if err != nil {
			logf("Failed to load Ad Bloom Filter from %s: %v", path, err)
		} else {
			e.adBlooms = append(e.adBlooms, bf)
			logf("Loaded Ad Bloom Filter for fast pre-filtering: %s", path)
		}
	}

	// Load security bloom filter
	for _, path := range strings.Split(secBloomPathsCsv, ",") {
		path = strings.TrimSpace(path)
		if path == "" {
			continue
		}
		bf, err := LoadBloomFilter(path)
		if err != nil {
			logf("Failed to load Security Bloom Filter from %s: %v", path, err)
		} else {
			e.secBlooms = append(e.secBlooms, bf)
			logf("Loaded Security Bloom Filter for fast pre-filtering: %s", path)
		}
	}
}

// SetFirewallChecker sets the Kotlin-side firewall checker.
// This is called before Start() to enable per-app DNS blocking.
func (e *Engine) SetFirewallChecker(checker FirewallChecker) {
	e.firewallChecker = checker
}

// SetAppResolver sets the Kotlin-side app name resolver for logging who made the request.
func (e *Engine) SetAppResolver(resolver AppResolver) {
	e.appResolver = resolver
}

// SetAppUidResolver sets the Kotlin-side UID→package resolver used for
// full-tunnel per-app DNS attribution and connection logging.
func (e *Engine) SetAppUidResolver(resolver AppUidResolver) {
	e.appUidResolver = resolver
}

// SetLogCallback sets the callback for DNS query events.
func (e *Engine) SetLogCallback(cb LogCallback) {
	e.logCallback = cb
}

// SetConnLogEnabled mirrors the app's "record logs" preference into the
// engine. Connection logging costs two binder round trips per flow to
// resolve the owning app, so it must be off whenever the user isn't
// recording logs. Safe to call at any time; takes effect for new flows.
func (e *Engine) SetConnLogEnabled(enabled bool) {
	e.connLogEnabled.Store(enabled)
	logf("SetConnLogEnabled: connection logging = %t", enabled)
}

// IsConnLogEnabled reports the current value.
func (e *Engine) IsConnLogEnabled() bool { return e.connLogEnabled.Load() }

// SetDoHBlocklist parses newline-separated domains of known DoH endpoints
// (e.g. from assets/blocklist_doh.txt). When client apps or browsers
// attempt to resolve these domains, the engine returns NXDOMAIN immediately,
// forcing them to fall back to plain DNS on port 53 (mitigating Issue #145).
func (e *Engine) SetDoHBlocklist(content string) {
	m := make(map[string]struct{})
	count := 0
	for _, line := range strings.Split(content, "\n") {
		domain := strings.TrimSpace(strings.ToLower(line))
		if domain == "" || strings.HasPrefix(domain, "#") || strings.HasPrefix(domain, "//") {
			continue
		}
		m[domain] = struct{}{}
		count++
	}
	e.mu.Lock()
	e.dohDomains = m
	e.mu.Unlock()
	logf("DoH Blocklist loaded: %d domains", count)
}

// IsDoHBlockingEnabled reports whether DoH blocking is currently active.
func (e *Engine) IsDoHBlockingEnabled() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return len(e.dohDomains) > 0
}

func (e *Engine) isDoHDomain(domain string) bool {
	e.mu.Lock()
	m := e.dohDomains
	e.mu.Unlock()
	if len(m) == 0 {
		return false
	}
	d := strings.ToLower(domain)
	for {
		if _, ok := m[d]; ok {
			return true
		}
		idx := strings.IndexByte(d, '.')
		if idx < 0 {
			break
		}
		d = d[idx+1:]
	}
	return false
}

// SetDNS configures the DNS settings.
// protocol: "PLAIN", "DOH", "DOT", "DOQ"
// primary: primary DNS server (e.g., "8.8.8.8")
// fallback: fallback DNS server (e.g., "1.1.1.1"), can be empty
// dohURL: DoH/DoQ server URL (e.g., "https://dns.cloudflare.com/dns-query")
func (e *Engine) SetDNS(protocol, primary, fallback, dohURL string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.protocol = protocol
	e.primaryDNS = primary
	e.fallbackDNS = fallback
	e.dohURL = dohURL
	if e.resolver != nil {
		e.resolver.Configure(ParseProtocol(protocol), primary, fallback, dohURL)
	}
}

// SetBlockResponseType sets how blocked domains are responded to.
// responseType: "CUSTOM_IP" (0.0.0.0), "NXDOMAIN", "REFUSED"
func (e *Engine) SetBlockResponseType(responseType string) {
	e.responseType = ParseResponseType(responseType)
}

// SetSafeSearch enables or disables SafeSearch enforcement.
func (e *Engine) SetSafeSearch(enabled bool) {
	e.safeSearch.SetEnabled(enabled)
}

// SetYouTubeRestricted enables or disables YouTube restricted mode.
func (e *Engine) SetYouTubeRestricted(enabled bool) {
	e.safeSearch.SetYouTubeRestricted(enabled)
}

// SetSplitDNSZones configures which domain zones should be resolved via the
// WireGuard DNS server instead of the upstream DNS. Zones are comma-separated
// suffixes (e.g., "internal,local,lan,corp"). The WireGuard DNS server is
// automatically extracted from the WireGuard config during Start().
func (e *Engine) SetSplitDNSZones(zones string) {
	e.splitZones = zones
	// If resolver is already running, update it
	if e.resolver != nil {
		e.applySplitDNS("")
	}
}

// applySplitDNS configures split-DNS on the resolver.
// If dnsServer is empty, only zones are updated (server kept from previous config).
func (e *Engine) applySplitDNS(dnsServer string) {
	if e.resolver == nil {
		return
	}
	zones := parseSplitZones(e.splitZones)
	if len(zones) > 0 && dnsServer != "" {
		e.resolver.SetSplitDNS(dnsServer, zones)
	} else if len(zones) == 0 {
		e.resolver.SetSplitDNS("", nil)
	}
}

// parseSplitZones parses comma-separated zone string into a slice.
func parseSplitZones(zones string) []string {
	if zones == "" {
		return nil
	}
	var result []string
	for _, z := range strings.Split(zones, ",") {
		z = strings.TrimSpace(strings.ToLower(z))
		if z != "" {
			result = append(result, z)
		}
	}
	return result
}

// logf logs a message (will appear in Android logcat via stderr).
func logf(format string, args ...interface{}) {
	msg := fmt.Sprintf("[BlockAds/Go] "+format, args...)
	fmt.Fprintln(os.Stderr, msg)
}

// ResolveHostForProtection resolves a hostname to an IP address.
// Used by Kotlin to bootstrap DNS server hostname resolution.
func ResolveHostForProtection(hostname string) string {
	ips, err := net.LookupHost(hostname)
	if err != nil || len(ips) == 0 {
		return ""
	}
	return ips[0]
}

// CheckDomainInTrieFile allows Kotlin to individually query a specific pre-compiled
// .trie file to see if it blocks a domain. Used for the "find blocking filter" feature.
func CheckDomainInTrieFile(filePath, domain string) bool {
	if filePath == "" || domain == "" {
		return false
	}
	t, err := LoadMmapTrie(filePath)
	if err != nil {
		return false
	}
	defer t.Close()
	return t.ContainsOrParent(domain)
}
