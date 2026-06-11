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
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

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

	adTries   []*MmapTrie
	adTrieIDs []string
	secTries  []*MmapTrie
	secTrieIDs []string

	// Bloom filters for fast pre-filtering (skip trie if definitely clean)
	adBlooms  []*BloomFilter
	secBlooms []*BloomFilter

	mu      sync.Mutex
	running bool
	tunFile *os.File

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

	// Stack-mode MITM state (Phase D). When both are non-nil, the stack
	// uses the MITM TCP handler; otherwise the Phase C direct-dial
	// passthrough handler is used.
	stackCertMgr    *CertManager
	stackMitmFilter *MitmFilter

	// UID resolver — supplied by Kotlin. When nil, flow-level UID lookup
	// falls back to UIDUnknown. Stored on the engine so both the stack
	// (once created) and any future consumer can pull from one place.
	uidResolver UIDResolver

	// protectFn is captured at Start time from the SocketProtector.
	// Handlers that dial outbound (direct flows, resolver fallbacks)
	// use it to ensure the socket bypasses the VPN.
	protectFn func(fd int) bool

	// Standalone Servers
	standaloneUdp *dns.Server
	standaloneTcp *dns.Server
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
		safeSearch:     NewSafeSearch(),
		responseType:   ResponseCustomIP,
		router:         router,
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
		if path == "" { continue }
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
		if path == "" { continue }
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
		if path == "" { continue }
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
		if path == "" { continue }
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

// SetLogCallback sets the callback for DNS query events.
func (e *Engine) SetLogCallback(cb LogCallback) {
	e.logCallback = cb
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

// Start begins processing packets from the TUN file descriptor.
// protector is called to protect sockets from VPN routing loop.
// wgConfigJSON: if non-empty, WireGuard is initialized from this JSON config
// BEFORE the packet read loop starts. Pass "" for DNS-only mode.
//
// This function blocks until Stop() is called.
//
// Pipeline:
//   TUN fd → DnsInterceptor → DNS (port 53) → adblock engine
//                            → non-DNS       → Router → OutboundAdapter
func (e *Engine) Start(fd int, protector SocketProtector, wgConfigJSON string) {
	e.mu.Lock()
	if e.running {
		e.mu.Unlock()
		return
	}
	e.running = true
	e.totalQueries.Store(0)
	e.blockedQueries.Store(0)

	// Create resolver with socket protection
	var protectFn func(fd int) bool
	if protector != nil {
		protectFn = func(fd int) bool {
			return protector.Protect(fd)
		}
	}
	e.protectFn = protectFn
	e.resolver = NewResolver(protectFn)
	e.resolver.Configure(ParseProtocol(e.protocol), e.primaryDNS, e.fallbackDNS, e.dohURL)
	e.mu.Unlock()

	// Duplicate fd to take proper ownership and avoid Android fdsan unique_fd crashes
	dupFd, err := syscall.Dup(fd)
	if err != nil {
		logf("Failed to dup TUN fd %d: %v", fd, err)
		e.running = false
		return
	}

	// Open TUN file descriptor using the dup'd fd
	e.tunFile = os.NewFile(uintptr(dupFd), "tun")
	if e.tunFile == nil {
		logf("Failed to open TUN fd %d", fd)
		e.running = false
		return
	}

	logf("Engine started, reading from TUN fd=%d", fd)

	// ── WireGuard Init (before read loop) ────────────────────────────
	// If wgConfigJSON is provided, set up WireGuard adapter FIRST.
	// WireGuard must be fully online before we start reading packets.
	if wgConfigJSON != "" {
		logf("WireGuard config provided, initializing...")

		wgCfg, err := ParseWgConfigJSON(wgConfigJSON)
		if err != nil {
			logf("WireGuard config parse error: %v", err)
			// Fall through to DNS-only mode
		} else {
			ipcConfig, err := BuildIpcConfig(wgCfg)
			if err != nil {
				logf("WireGuard IPC config build error: %v", err)
			} else {
				// Create a virtual channelTUN for wireguard-go.
				// DnsInterceptor is the sole reader of the real TUN.
				// Non-DNS packets → channelTUN.Inject() → wireguard-go.
				// Decrypted responses → channelTUN.Write() → real TUN.
				tunDevice := newChannelTUN(e.tunFile)
				wgAdapter, err := NewWgOutbound(tunDevice, ipcConfig, e.protectFn)
				if err != nil {
					logf("WireGuard adapter create error: %v", err)
				} else {
					if err := wgAdapter.Start(); err != nil {
						logf("WireGuard adapter start error: %v", err)
					} else {
						e.router.SetAdapter(wgAdapter)
						logf("WireGuard adapter fully initialized and active")

						// Configure split-DNS using WireGuard's DNS server
						if len(wgCfg.Interface.DNS) > 0 && e.splitZones != "" {
							e.applySplitDNS(wgCfg.Interface.DNS[0])
						}
					}
				}
			}
		}
	} else {
		logf("No WireGuard config, running in DNS-only mode")
	}

	// ── Optional TCP/IP Stack (AdGuard-style parallel path) ──────────
	// When enabled, non-DNS packets are redirected from the interceptor
	// into the stack instead of going through the legacy Router. The
	// stack terminates each flow and invokes the registered handler
	// (Phase C: direct-dial passthrough; Phase D: MITM for HTTPS).
	if e.useTcpStack.Load() {
		if err := e.startTcpStackParallel(); err != nil {
			logf("TcpIpStack parallel start failed, falling back to legacy path: %v", err)
		}
	}

	// ── Packet Read Loop ─────────────────────────────────────────────
	// DnsInterceptor reads from TUN, routes DNS to adblock engine,
	// and non-DNS to Router → active OutboundAdapter (or to the
	// TcpIpStack pipe when enabled).
	// This call blocks until Stop() is called.
	e.interceptor.Run(e.tunFile)

	logf("Engine stopped")
}

// Stop stops the engine.
func (e *Engine) Stop() {
	e.mu.Lock()

	e.running = false

	// Stop the interceptor (breaks the read loop)
	if e.interceptor != nil {
		e.interceptor.Stop()
	}

	// Stop the router and its active adapter
	if e.router != nil {
		e.router.Stop()
	}

	// Tear down TCP/IP stack, if running.
	stack := e.tcpStack
	e.tcpStack = nil
	pipe := e.tcpStackPipe.Swap(nil)

	// Close TUN, clear caches — all while locked
	if e.tunFile != nil {
		e.tunFile.Close()
		e.tunFile = nil
	}
	
	oldResolver := e.resolver
	e.resolver = nil
	
	e.safeSearch.ClearCache()

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

	oldUdp := e.standaloneUdp
	e.standaloneUdp = nil
	
	oldTcp := e.standaloneTcp
	e.standaloneTcp = nil

	oldUdp6 := e.standaloneUdp6
	e.standaloneUdp6 = nil

	oldTcp6 := e.standaloneTcp6
	e.standaloneTcp6 = nil

	e.mu.Unlock()

	// Shutdown servers OUTSIDE the lock to prevent deadlocks with ServeDNS handlers
	if oldUdp != nil {
		oldUdp.Shutdown()
	}
	if oldTcp != nil {
		oldTcp.Shutdown()
	}
	if oldUdp6 != nil {
		oldUdp6.Shutdown()
	}
	if oldTcp6 != nil {
		oldTcp6.Shutdown()
	}
	if oldResolver != nil {
		oldResolver.Shutdown()
	}
	// Tear down the TCP/IP stack outside the lock — Stop() blocks on
	// dispatcher goroutines. Close the pipe first so the outbound
	// writer goroutine unblocks from Pop(), then stop the stack.
	if pipe != nil {
		pipe.Close()
	}
	if stack != nil {
		stack.Stop()
	}
}

// IsRunning returns whether the engine is currently running.
func (e *Engine) IsRunning() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.running
}

// GetStats returns engine statistics as JSON.
func (e *Engine) GetStats() string {
	stats := Stats{
		TotalQueries:   e.totalQueries.Load(),
		BlockedQueries: e.blockedQueries.Load(),
	}
	data, _ := json.Marshal(stats)
	return string(data)
}

// ── Standalone DNS Server (Root/Proxy Mode) ──────────────────────────────────

// ServeDNS handles incoming DNS queries directly from a socket (no TUN fd).
func (e *Engine) ServeDNS(w dns.ResponseWriter, r *dns.Msg) {
	startTime := time.Now()
	if len(r.Question) == 0 {
		return
	}

	domain := strings.ToLower(r.Question[0].Name)
	domain = strings.TrimSuffix(domain, ".")
	queryType := r.Question[0].Qtype
	appName := "RootProxy"
	// Try to resolve the real app name from the source port of the incoming connection.
	// iptables REDIRECT preserves the original source port, so we can look up the UID
	// in /proc/net/udp by matching that port.
	if e.appResolver != nil {
		if addr := w.RemoteAddr(); addr != nil {
			srcPort := 0
			srcIP := net.IPv4(127, 0, 0, 1)

			switch a := addr.(type) {
			case *net.UDPAddr:
				srcPort = a.Port
				if a.IP != nil {
					srcIP = a.IP
				}
			case *net.TCPAddr:
				srcPort = a.Port
				if a.IP != nil {
					srcIP = a.IP
				}
			default:
				// Fallback: parse "host:port" string
				if host, portStr, err := net.SplitHostPort(addr.String()); err == nil {
					if p, err2 := fmt.Sscanf(portStr, "%d", &srcPort); p == 1 && err2 == nil {
						if parsed := net.ParseIP(host); parsed != nil {
							srcIP = parsed
						}
					}
				}
			}

			if srcPort > 0 {
				// Normalize to IPv4 bytes if possible, otherwise use raw 16-byte IPv6
				ipBytes := srcIP.To4()
				if ipBytes == nil {
					ipBytes = srcIP.To16()
				}
				if ipBytes == nil {
					ipBytes = []byte{127, 0, 0, 1}
				}

				resolved := e.appResolver.ResolveApp(
					srcPort,
					ipBytes,
					[]byte{127, 0, 0, 1},
					53,
				)
				if resolved != "" {
					appName = resolved
				}
			}
		}
	}

	// 0. Firewall (App Blocker) Check
	if e.firewallChecker != nil && appName != "" && appName != "RootProxy" {
		if e.firewallChecker.ShouldBlock(appName) {
			e.standaloneBlock(w, r, "firewall", appName, startTime)
			return
		}
	}

	// 1. Custom Rules Override
	if e.domainChecker != nil {
		override := e.domainChecker.HasCustomRule(domain)
		if override == 0 {
			e.standaloneForward(w, r, appName, startTime)
			return
		} else if override == 1 {
			reason := e.domainChecker.GetBlockReason(domain)
			if reason == "" {
				reason = "custom"
			}
			e.standaloneBlock(w, r, reason, appName, startTime)
			return
		}
	}

	// 2. SafeSearch / YouTube Check
	ssResult := e.safeSearch.Check(domain, queryType)
	if ssResult.Action == ActionRedirect {
		if e.standaloneRedirect(w, r, ssResult.RedirectDomain, appName, startTime) {
			return
		}
	}
	if isYT, ytDomain := e.safeSearch.CheckYouTube(domain, queryType); isYT {
		if e.standaloneRedirect(w, r, ytDomain, appName, startTime) {
			return
		}
	}

	// 3. Fast Native Go Tries (Security then Ads)
	e.mu.Lock()
	secBlooms := e.secBlooms
	secTries := e.secTries
	adBlooms := e.adBlooms
	adTries := e.adTries
	e.mu.Unlock()

	var matchedIDs []string

	for i, secTrie := range secTries {
		if secTrie == nil { continue }
		var secBloom *BloomFilter
		if i < len(secBlooms) { secBloom = secBlooms[i] }
		if secBloom == nil || secBloom.MightContainDomainOrParent(domain) {
			if secTrie.ContainsOrParent(domain) {
				id := "security"
				if i < len(e.secTrieIDs) { id = e.secTrieIDs[i] }
				matchedIDs = append(matchedIDs, id)
			}
		}
	}

	for i, adTrie := range adTries {
		if adTrie == nil { continue }
		var adBloom *BloomFilter
		if i < len(adBlooms) { adBloom = adBlooms[i] }
		if adBloom == nil || adBloom.MightContainDomainOrParent(domain) {
			if adTrie.ContainsOrParent(domain) {
				id := "filter_list"
				if i < len(e.adTrieIDs) { id = e.adTrieIDs[i] }
				matchedIDs = append(matchedIDs, id)
			}
		}
	}

	if len(matchedIDs) > 0 {
		e.standaloneBlock(w, r, strings.Join(matchedIDs, ","), appName, startTime)
		return
	}

	// 4. Fallback Kotlin DomainChecker
	if e.domainChecker != nil && e.domainChecker.IsBlocked(domain) {
		reason := e.domainChecker.GetBlockReason(domain)
		if reason == "" {
			reason = "filter_list"
		}
		e.standaloneBlock(w, r, reason, appName, startTime)
		return
	}

	// 5. Forward to Upstream
	e.standaloneForward(w, r, appName, startTime)
}

// lookupIP resolves a domain to an IP address using the Engine's internal resolver.
// It is used by the MITM proxy to bypass Android's problematic system DNS resolver
// when the app itself is excluded from the VPN.
// Uses the full Resolve() pipeline (DoH/DoT/DoQ/Plain + fallback) so it works
// regardless of the user's chosen DNS protocol. If the configured upstream is
// unreachable (transport failure), falls back to direct UDP queries against
// well-known public resolvers so browser passthrough still works when the
// user's DNS provider is temporarily down. A successful DNS response with
// no A record (NXDOMAIN / empty answer) is NOT retried — that's treated as
// intentional filtering by the user's configured DNS.
func (e *Engine) lookupIP(domain string) (net.IP, error) {
	e.mu.Lock()
	resolver := e.resolver
	e.mu.Unlock()

	if resolver == nil {
		return nil, fmt.Errorf("engine resolver not initialized")
	}

	// Build a DNS A-query
	msg := new(dns.Msg)
	msg.SetQuestion(dns.Fqdn(domain), dns.TypeA)
	msg.RecursionDesired = true

	rawQuery, err := msg.Pack()
	if err != nil {
		return nil, fmt.Errorf("pack query: %w", err)
	}

	// Use the full Resolve() pipeline (primary + fallback, respects DoH/DoT/DoQ)
	resp, err := resolver.Resolve(rawQuery)
	if err != nil {
		// Primary + configured fallback both failed at the transport
		// level. Try unfiltered public DNS over plain UDP so the MITM
		// proxy doesn't have to fall through to Go's system resolver
		// (which is unreliable on Android for VPN-excluded processes).
		for _, server := range []string{"1.1.1.1:53", "8.8.8.8:53"} {
			if ip, fbErr := resolver.ResolveARecord(domain, server); fbErr == nil && ip != nil {
				logf("lookupIP: %s resolved via public fallback %s (primary err: %v)", domain, server, err)
				return ip, nil
			}
		}
		return nil, fmt.Errorf("resolve %s: %w", domain, err)
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

func (e *Engine) standaloneBlock(w dns.ResponseWriter, r *dns.Msg, blockedBy, appName string, startTime time.Time) {
	m := new(dns.Msg)
	m.SetReply(r)

	switch e.responseType {
	case ResponseNXDomain:
		m.Rcode = dns.RcodeNameError
	case ResponseRefused:
		m.Rcode = dns.RcodeRefused
	default:
		m.Rcode = dns.RcodeSuccess
		if r.Question[0].Qtype == dns.TypeA {
			rr, _ := dns.NewRR(fmt.Sprintf("%s 300 IN A 0.0.0.0", r.Question[0].Name))
			m.Answer = append(m.Answer, rr)
		} else if r.Question[0].Qtype == dns.TypeAAAA {
			rr, _ := dns.NewRR(fmt.Sprintf("%s 300 IN AAAA ::", r.Question[0].Name))
			m.Answer = append(m.Answer, rr)
		}
	}

	_ = w.WriteMsg(m)
	e.totalQueries.Add(1)
	e.blockedQueries.Add(1)
	elapsed := time.Since(startTime).Milliseconds()
	e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), true, r.Question[0].Qtype, elapsed, appName, "", blockedBy)
}

func (e *Engine) standaloneForward(w dns.ResponseWriter, r *dns.Msg, appName string, startTime time.Time) {
	raw, err := r.Pack()
	if err != nil {
		dns.HandleFailed(w, r)
		return
	}

	// Grab resolver snapshot under lock to avoid nil dereference during shutdown
	e.mu.Lock()
	resolver := e.resolver
	e.mu.Unlock()
	if resolver == nil {
		dns.HandleFailed(w, r)
		return
	}

	respRaw, err := resolver.Resolve(raw)
	if err != nil {
		logf("DNS resolve failed standalone %s: %v", r.Question[0].Name, err)
		dns.HandleFailed(w, r)
		e.totalQueries.Add(1)
		elapsed := time.Since(startTime).Milliseconds()
		e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), false, r.Question[0].Qtype, elapsed, appName, "", "")
		return
	}

	var respMsg dns.Msg
	if err := respMsg.Unpack(respRaw); err != nil {
		dns.HandleFailed(w, r)
		return
	}

	if isUpstreamBlocked(respRaw) {
		e.totalQueries.Add(1)
		e.blockedQueries.Add(1)
		elapsed := time.Since(startTime).Milliseconds()
		e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), true, r.Question[0].Qtype, elapsed, appName, "", "upstream_dns")
	} else {
		e.totalQueries.Add(1)
		elapsed := time.Since(startTime).Milliseconds()
		e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), false, r.Question[0].Qtype, elapsed, appName, "", "")
	}

	respMsg.Id = r.Id
	_ = w.WriteMsg(&respMsg)
}

func (e *Engine) standaloneRedirect(w dns.ResponseWriter, r *dns.Msg, redirectDomain, appName string, startTime time.Time) bool {
	ip := e.safeSearch.GetCachedIP(redirectDomain)
	if ip == nil {
		// Grab resolver snapshot under lock to avoid nil dereference during shutdown
		e.mu.Lock()
		resolver := e.resolver
		e.mu.Unlock()
		if resolver == nil {
			return false
		}

		var err error
		ip, err = resolver.ResolveARecord(redirectDomain, e.primaryDNS)
		if err != nil {
			return false
		}
		e.safeSearch.CacheIP(redirectDomain, ip)
	}

	m := new(dns.Msg)
	m.SetReply(r)
	m.Rcode = dns.RcodeSuccess

	if r.Question[0].Qtype == dns.TypeA {
		rr, _ := dns.NewRR(fmt.Sprintf("%s 300 IN A %s", r.Question[0].Name, ip.String()))
		m.Answer = append(m.Answer, rr)
	}

	_ = w.WriteMsg(m)
	e.totalQueries.Add(1)
	elapsed := time.Since(startTime).Milliseconds()
	e.notifyLog(strings.TrimSuffix(r.Question[0].Name, "."), false, r.Question[0].Qtype, elapsed, appName, ip.String(), "")
	return true
}

// StartStandalone starts the engine in DNS-only standalone mode on 127.0.0.1:port
// It bypasses TUN and directly serves incoming UDP/TCP DNS queries.
func (e *Engine) StartStandalone(port int) error {
	e.mu.Lock()

	var oldUdp, oldTcp, oldUdp6, oldTcp6 *dns.Server
	var oldResolver *Resolver

	// If already running, capture pointers to release outside lock
	if e.running {
		oldUdp = e.standaloneUdp
		e.standaloneUdp = nil
		oldTcp = e.standaloneTcp
		e.standaloneTcp = nil
		oldUdp6 = e.standaloneUdp6
		e.standaloneUdp6 = nil
		oldTcp6 = e.standaloneTcp6
		e.standaloneTcp6 = nil
		oldResolver = e.resolver
		e.resolver = nil
		e.running = false
	}

	e.running = true
	e.totalQueries.Store(0)
	e.blockedQueries.Store(0)

	// Since we are not using a TUN interface, we don't need a SocketProtector
	// Root/Proxy mode traffic naturally avoids loops due to iptables owner UID matching.
	e.resolver = NewResolver(nil)
	e.resolver.Configure(ParseProtocol(e.protocol), e.primaryDNS, e.fallbackDNS, e.dohURL)
	e.mu.Unlock()

	// Shutdown old servers outside the lock
	if oldUdp != nil {
		oldUdp.Shutdown()
	}
	if oldTcp != nil {
		oldTcp.Shutdown()
	}
	if oldUdp6 != nil {
		oldUdp6.Shutdown()
	}
	if oldTcp6 != nil {
		oldTcp6.Shutdown()
	}
	if oldResolver != nil {
		oldResolver.Shutdown()
	}

	// Bind strictly to IPv4 AND IPv6 loopback separately for maximum security and proxy accuracy
	addr4 := fmt.Sprintf("127.0.0.1:%d", port)
	addr6 := fmt.Sprintf("[::1]:%d", port)

	udpServer := &dns.Server{Addr: addr4, Net: "udp", Handler: dns.HandlerFunc(e.ServeDNS)}
	tcpServer := &dns.Server{Addr: addr4, Net: "tcp", Handler: dns.HandlerFunc(e.ServeDNS)}
	
	udpServer6 := &dns.Server{Addr: addr6, Net: "udp6", Handler: dns.HandlerFunc(e.ServeDNS)}
	tcpServer6 := &dns.Server{Addr: addr6, Net: "tcp6", Handler: dns.HandlerFunc(e.ServeDNS)}

	e.mu.Lock()
	e.standaloneUdp = udpServer
	e.standaloneTcp = tcpServer
	e.standaloneUdp6 = udpServer6
	e.standaloneTcp6 = tcpServer6
	e.mu.Unlock()

	errChan := make(chan error, 4)

	go func() {
		if err := udpServer.ListenAndServe(); err != nil {
			logf("Standalone UDP IPv4 stopped: %v", err)
			errChan <- err
		}
	}()
	go func() {
		if err := tcpServer.ListenAndServe(); err != nil {
			logf("Standalone TCP IPv4 stopped: %v", err)
			errChan <- err
		}
	}()
	go func() {
		if err := udpServer6.ListenAndServe(); err != nil {
			logf("Standalone UDP IPv6 stopped: %v", err)
			// IPv6 might fail on v4-only kernels, ignore to prevent crashing the whole engine
		}
	}()
	go func() {
		if err := tcpServer6.ListenAndServe(); err != nil {
			logf("Standalone TCP IPv6 stopped: %v", err)
		}
	}()

	// Give servers a moment to bind
	time.Sleep(100 * time.Millisecond)

	// Check if IPv4 servers failed to start (critical error)
	select {
	case err := <-errChan:
		return fmt.Errorf("IPv4 Server failed to start: %v", err)
	default:
	}

	logf("Engine started in STANDALONE mode on %s and %s", addr4, addr6)
	return nil
}

// ── HTTPS MITM API ───────────────────────────────────────────────────────────
// gomobile-compatible methods for controlling HTTPS MITM filtering.
// Historically a CONNECT-based HTTP proxy on 127.0.0.1:8080; since
// Phase E the handler is attached to the userspace TCP/IP stack
// (StartStackMitm + SetUseTcpStack).

// StartStackMitm initialises MITM state for the userspace TCP/IP
// stack path. Call this in addition to SetUseTcpStack(true) to have
// the stack handle HTTPS filtering. The persistent Root CA is loaded
// from (or generated in) certDir; the returned PEM must be installed
// on-device as a user CA for browsers to accept intercepted TLS.
//
// Returns empty string on error (check logs).
//
// Kotlin usage:
//
//	adapter.setUseTcpStack(true)
//	val caPem = engine.startStackMitm(context.filesDir.absolutePath)
//	// Write caPem to storage and prompt the user to install it.
//	engine.setMitmAllowedUIDs(browserUids.joinToString(","))
func (e *Engine) StartStackMitm(certDir string) string {
	certMgr, err := NewCertManager(certDir)
	if err != nil {
		logf("StartStackMitm: cert manager init failed: %v", err)
		return ""
	}
	certMgr.WarmLocalAssetCert()

	e.mu.Lock()
	e.stackCertMgr = certMgr
	if e.stackMitmFilter == nil {
		e.stackMitmFilter = NewMitmFilter()
	}
	e.mu.Unlock()

	return certMgr.GetCACertPEM()
}

// StopStackMitm clears stack-mode MITM state. The stack itself keeps
// running on the direct-dial handler after this call.
func (e *Engine) StopStackMitm() {
	e.mu.Lock()
	e.stackCertMgr = nil
	e.stackMitmFilter = nil
	e.mu.Unlock()
}

// SetUseTcpStack toggles whether non-DNS packets are routed into the
// userspace TCP/IP stack. When true (recommended for HTTPS filtering),
// the DnsInterceptor redirects non-DNS packets into the stack for
// per-flow processing (direct dial or MITM depending on whether
// StartStackMitm was called). When false, non-DNS packets go through
// the Router → outbound adapter path for DNS-only or WireGuard use.
//
// The flag must be set before Engine.Start. Runtime toggling after
// Start is not supported.
func (e *Engine) SetUseTcpStack(enabled bool) {
	e.useTcpStack.Store(enabled)
}

// IsUsingTcpStack reports the current flag value.
func (e *Engine) IsUsingTcpStack() bool { return e.useTcpStack.Load() }

// SetUIDResolver registers the Kotlin-implemented resolver used to look
// up the owning app UID for each TCP/UDP flow terminated by the
// userspace TCP/IP stack. Typically wired once at VPN start.
//
// Passing nil clears the resolver; flows will then report UIDUnknown.
func (e *Engine) SetUIDResolver(r UIDResolver) {
	e.mu.Lock()
	e.uidResolver = r
	stack := e.tcpStack
	e.mu.Unlock()

	if stack != nil {
		stack.SetUIDResolver(r)
	}
}

// startTcpStackParallel brings up the userspace TCP/IP stack on a
// packet pipe that the DnsInterceptor will feed from. The legacy
// mitmProxy and Router remain in place — only non-DNS packets diverge
// into the stack for Phase C testing. Called with e.mu unlocked
// (sets up state visible to other goroutines atomically via fields
// protected by locks where necessary).
func (e *Engine) startTcpStackParallel() error {
	pipe := newPacketPipe()
	stack := NewTcpIpStack()

	e.mu.Lock()
	uidr := e.uidResolver
	protectFn := e.protectFn
	certMgr := e.stackCertMgr
	filter := e.stackMitmFilter
	mtu := uint32(defaultTunMTU)
	e.tcpStack = stack
	e.mu.Unlock()
	e.tcpStackPipe.Store(pipe)

	stack.SetUIDResolver(uidr)
	if certMgr != nil && filter != nil {
		// Phase D path — MITM handler applies the full filtering flow.
		stack.SetTcpHandler(newMitmTcpHandler(certMgr, filter, e, uidr, protectFn))
		logf("TcpIpStack: MITM handler registered")
	} else {
		// Phase C default — direct-dial passthrough, no MITM.
		stack.SetTcpHandler(newProtectedTcpHandler(uidr, protectFn))
	}
	stack.SetUdpHandler(newProtectedUdpHandler(uidr, protectFn))

	if err := stack.Start(pipe, mtu); err != nil {
		e.mu.Lock()
		e.tcpStack = nil
		e.mu.Unlock()
		e.tcpStackPipe.Store(nil)
		pipe.Close()
		return fmt.Errorf("stack start: %w", err)
	}

	// Drain outbound packets from the stack and write them back to the
	// real TUN so responses reach the originating app. Runs until the
	// pipe is closed (Stop → pipe.Close → Pop returns nil).
	go e.runTcpStackOutboundWriter(pipe)

	logf("TcpIpStack: parallel path started (flag=on)")
	return nil
}

// runTcpStackOutboundWriter drains outbound packets emitted by the
// stack and forwards them to the real TUN device. The TUN file is
// captured once at start so the hot path doesn't acquire e.mu on
// every packet; if Stop closes the TUN, tun.Write returns an error
// and the goroutine exits cleanly.
func (e *Engine) runTcpStackOutboundWriter(p *packetPipe) {
	e.mu.Lock()
	tun := e.tunFile
	e.mu.Unlock()
	if tun == nil {
		logf("TcpIpStack: outbound writer started with nil TUN, exiting")
		return
	}

	var written, dropped int64
	defer func() {
		logf("TcpIpStack: outbound writer stopped (written=%d dropped=%d)", written, dropped)
	}()

	for {
		pkt := p.Pop()
		if pkt == nil {
			return
		}
		if _, err := tun.Write(pkt); err != nil {
			dropped++
			logf("TcpIpStack: TUN write error after %d packets: %v", written, err)
			return
		}
		written++
	}
}

// defaultTunMTU matches the VpnService.Builder.setMtu(1500) default in
// AdBlockVpnService.kt. Keeping them aligned avoids fragmentation in
// the userspace stack.
const defaultTunMTU = 1500

// localAssetSynthIP is the synthetic IPv4 address handed out for
// resolution of LocalAssetHost. RFC 5737 reserves 198.51.100.0/24 for
// documentation use; nothing in production routes there, so the
// browser SYN unambiguously enters our TUN where the userspace stack
// catches it and dispatches to mitm_handler's local-asset branch
// based on the SNI.
var localAssetSynthIP = net.IPv4(198, 51, 100, 1)

// IsMitmActive returns true when the HTTPS MITM filter is active
// (stack handler registered with cert manager + filter).
func (e *Engine) IsMitmActive() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.stackCertMgr != nil
}

// GetMitmCACert returns the PEM-encoded Root CA certificate. Reads
// from disk at certDir; stack MITM uses the same ca.crt file.
func (e *Engine) GetMitmCACert(certDir string) string {
	e.mu.Lock()
	certMgr := e.stackCertMgr
	e.mu.Unlock()

	if certMgr != nil {
		return certMgr.GetCACertPEM()
	}

	certPath := filepath.Join(certDir, caCertFile)
	if !fileExists(certPath) {
		return ""
	}
	data, err := os.ReadFile(certPath)
	if err != nil {
		logf("Failed to read persistent CA cert: %v", err)
		return ""
	}
	return string(data)
}

// SetMitmAllowedUIDs sets the list of Android app UIDs that are allowed
// for MITM interception (typically browser UIDs).
//
// uidsCsv: comma-separated UIDs, e.g., "10145,10200,10201"
// gomobile doesn't support []int, so we use a CSV string.
//
// Kotlin usage:
//
//	val browserUids = listOf(chromeUid, firefoxUid, braveUid)
//	engine.setMitmAllowedUIDs(browserUids.joinToString(","))
func (e *Engine) SetMitmAllowedUIDs(uidsCsv string) {
	e.mu.Lock()
	stackFilter := e.stackMitmFilter
	e.mu.Unlock()

	if stackFilter == nil {
		logf("MITM: SetAllowedUIDs called but stack MITM is not active")
		return
	}

	var uids []int
	for _, s := range strings.Split(uidsCsv, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		uid := 0
		for _, c := range s {
			if c >= '0' && c <= '9' {
				uid = uid*10 + int(c-'0')
			}
		}
		if uid > 0 {
			uids = append(uids, uid)
		}
	}
	stackFilter.SetAllowedUIDs(uids)
}

// SetExtraPassthroughSuffixes loads the runtime passthrough list onto
// the stack-mode MITM filter. Call this after StartStackMitm. The
// input is a newline-separated string (the raw contents of
// assets/https_passthrough.txt); blank lines and # / // comments are
// ignored. gomobile doesn't bridge []string cleanly, so we use a
// single string and split inside Go.
//
// Kotlin usage:
//
//	val raw = context.assets.open("https_passthrough.txt").bufferedReader().readText()
//	engine.setExtraPassthroughSuffixes(raw)
func (e *Engine) SetExtraPassthroughSuffixes(content string) {
	e.mu.Lock()
	filter := e.stackMitmFilter
	e.mu.Unlock()
	if filter == nil {
		logf("SetExtraPassthroughSuffixes: stack MITM not active")
		return
	}
	filter.SetExtraPassthroughSuffixes(strings.Split(content, "\n"))
}

// SetScriptletRules parses +js() rules from the supplied filter-list
// content and rebuilds the in-memory store. Lines that aren't +js()
// rules are ignored, so the same content that drives cosmetic CSS
// can be passed verbatim. Pass an empty string to clear the store.
//
// Kotlin usage:
//
//	val raw = filterRepo.allFilterTextConcatenated()
//	engine.setScriptletRules(raw)
func (e *Engine) SetScriptletRules(content string) {
	if content == "" {
		SetScriptletStore(nil)
		return
	}
	rules := parseScriptletRules(content)
	if len(rules) == 0 {
		SetScriptletStore(nil)
		logf("Scriptlet rules: parsed 0 rules from %d-byte input", len(content))
		return
	}
	store := buildScriptletStore(rules)
	SetScriptletStore(store)
	logf("Scriptlet rules: parsed %d rules (%d global, %d host-bound)",
		len(rules), len(store.all), len(store.byHost))
}

// SetCosmeticCSS sets the minified CSS string to inject into HTML responses
// for cosmetic ad hiding (e.g., EasyList `##.ad-banner` rules).
//
// Kotlin usage:
//
//	val css = CosmeticRuleParser.parseToCss(lines)
//	engine.setCosmeticCSS(css)
func (e *Engine) SetCosmeticCSS(css string) {
	SetCosmeticCSS(css)
}

// ── AdBlockChecker implementation ────────────────────────────────────────────
// IsDomainBlocked satisfies the AdBlockChecker interface used by the MITM
// proxy.  It replicates the exact same blocking pipeline used for DNS queries:
//   CustomRule(allow override) → SecurityTrie → AdTrie → Kotlin DomainChecker.
func (e *Engine) IsDomainBlocked(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	if host == "" {
		return false
	}

	// ── Custom rule allow/block override ──
	if e.domainChecker != nil {
		override := e.domainChecker.HasCustomRule(host)
		if override == 0 {
			return false // explicitly allowed
		}
		if override == 1 {
			return true // explicitly blocked
		}
	}

	// ── Security trie (Bloom pre-filter → Mmap Trie) ──
	e.mu.Lock()
	secBlooms := e.secBlooms
	secTries := e.secTries
	adBlooms := e.adBlooms
	adTries := e.adTries
	e.mu.Unlock()

	for i, secTrie := range secTries {
		if secTrie == nil { continue }
		var secBloom *BloomFilter
		if i < len(secBlooms) {
			secBloom = secBlooms[i]
		}
		if secBloom == nil || secBloom.MightContainDomainOrParent(host) {
			if secTrie.ContainsOrParent(host) {
				return true
			}
		}
	}

	// ── Ad trie (Bloom pre-filter → Mmap Trie) ──
	for i, adTrie := range adTries {
		if adTrie == nil { continue }
		var adBloom *BloomFilter
		if i < len(adBlooms) {
			adBloom = adBlooms[i]
		}
		if adBloom == nil || adBloom.MightContainDomainOrParent(host) {
			if adTrie.ContainsOrParent(host) {
				return true
			}
		}
	}

	// ── Kotlin DomainChecker fallback ──
	if e.domainChecker != nil {
		if e.domainChecker.IsBlocked(host) {
			return true
		}
	}

	return false
}

// handleDNSQuery processes a single DNS query.
func (e *Engine) handleDNSQuery(queryInfo *DNSQueryInfo) {
	// Early exit: if engine was stopped while this goroutine was queued,
	// don't touch any shared state — the resources may already be freed.
	e.mu.Lock()
	running := e.running
	e.mu.Unlock()
	if !running {
		return
	}

	startTime := time.Now()
	domain := strings.ToLower(queryInfo.Domain)

	// Local asset host: synthesize a response with a routable IP from
	// the RFC 5737 documentation range so the browser can SYN to it
	// and have the packet enter our TUN. The userspace stack catches
	// the flow and serves cosmetic.css from memory based on SNI. This
	// replaces the legacy CONNECT-via-setHttpProxy path.
	if domain == LocalAssetHost {
		response := BuildRedirectResponse(queryInfo, localAssetSynthIP)
		e.writeToTUN(response)
		e.totalQueries.Add(1)
		return
	}

	// Fetch App Name for logging (and firewall)
	appName := ""
	if e.appResolver != nil {
		appName = e.appResolver.ResolveApp(
			int(queryInfo.SourcePort),
			[]byte(queryInfo.SourceIP),
			[]byte(queryInfo.DestIP),
			int(queryInfo.DestPort),
		)
	}

	// Firewall check (per-app blocking via Kotlin callback)
	if e.firewallChecker != nil && appName != "" {
		if e.firewallChecker.ShouldBlock(appName) {
			e.handleFirewallBlock(queryInfo, appName, startTime)
			return
		}
	}

	// Split-DNS check: forward matching domains through WireGuard tunnel
	// Must happen before ad-blocking so internal domains are never blocked.
	e.mu.Lock()
	resolver := e.resolver
	e.mu.Unlock()
	if resolver != nil {
		resolver.mu.RLock()
		splitDNS := resolver.splitDNS
		splitZones := resolver.splitZones
		resolver.mu.RUnlock()

		if splitDNS != "" && len(splitZones) > 0 && resolver.matchesSplitZone(domain) {
			// Route this DNS packet through the WireGuard tunnel via the router
			e.handleSplitDNSForward(queryInfo, appName, startTime)
			return
		}
	}

	// SafeSearch check
	ssResult := e.safeSearch.Check(domain, queryInfo.QueryType)
	if ssResult.Action == ActionRedirect {
		if e.handleSafeSearchRedirect(queryInfo, ssResult.RedirectDomain, appName, startTime) {
			return
		}
		// If redirect IP resolution failed, fall through to normal resolution
	}

	// YouTube restricted mode check
	if isYT, ytDomain := e.safeSearch.CheckYouTube(domain, queryInfo.QueryType); isYT {
		if e.handleSafeSearchRedirect(queryInfo, ytDomain, appName, startTime) {
			return
		}
	}

	// ── Early Return for Custom Rules Override ──
	// Checks custom allow/block and whitelist rules in Kotlin BEFORE checking the fast-path Tries.
	// 1 = Block Override, 0 = Allow Override, -1 = No Custom Rule
	if e.domainChecker != nil {
		customOverride := e.domainChecker.HasCustomRule(domain)
		if customOverride == 0 {
			// Explicitly allowed by user or whitelist, skip trie checks
			e.handleForward(queryInfo, appName, startTime)
			return
		} else if customOverride == 1 {
			// Explicitly blocked by user custom rules
			blockedBy := e.domainChecker.GetBlockReason(domain)
			if blockedBy == "" {
				blockedBy = "custom"
			}
			e.handleBlockedDomain(queryInfo, blockedBy, appName, startTime)
			return
		}
	}

	// Fast Native Go Domain blocking check — Bloom Filter pre-filter + Mmap Trie
	//
	// Step 1: Bloom Filter (O(1)) — if it says "definitely not blocked", skip the trie entirely.
	// Step 2: Mmap Trie (O(L)) — confirm that the domain is actually blocked.
	//
	// This eliminates trie traversal for ~90%+ of clean queries.

	// Snapshot tries under lock to avoid use-after-free when Stop() closes them.
	e.mu.Lock()
	secBlooms := e.secBlooms
	secTries := e.secTries
	secTrieIDs := e.secTrieIDs
	adBlooms := e.adBlooms
	adTries := e.adTries
	adTrieIDs := e.adTrieIDs
	e.mu.Unlock()

	// Collect ALL matching filter IDs so every filter gets attribution in statistics
	var matchedIDs []string

	// Security domains
	for i, secTrie := range secTries {
		if secTrie == nil { continue }
		var secBloom *BloomFilter
		if i < len(secBlooms) {
			secBloom = secBlooms[i]
		}
		if secBloom == nil || secBloom.MightContainDomainOrParent(domain) {
			if secTrie.ContainsOrParent(domain) {
				id := "security"
				if i < len(secTrieIDs) {
					id = secTrieIDs[i]
				}
				matchedIDs = append(matchedIDs, id)
			}
		}
	}

	// Ad domains
	for i, adTrie := range adTries {
		if adTrie == nil { continue }
		var adBloom *BloomFilter
		if i < len(adBlooms) {
			adBloom = adBlooms[i]
		}
		if adBloom == nil || adBloom.MightContainDomainOrParent(domain) {
			if adTrie.ContainsOrParent(domain) {
				id := "filter_list"
				if i < len(adTrieIDs) {
					id = adTrieIDs[i]
				}
				matchedIDs = append(matchedIDs, id)
			}
		}
	}

	if len(matchedIDs) > 0 {
		e.handleBlockedDomain(queryInfo, strings.Join(matchedIDs, ","), appName, startTime)
		return
	}

	// Forward to upstream DNS
	e.handleForward(queryInfo, appName, startTime)
}

// handleSafeSearchRedirect handles a SafeSearch/YouTube redirect.
func (e *Engine) handleSafeSearchRedirect(queryInfo *DNSQueryInfo, redirectDomain, appName string, startTime time.Time) bool {
	// Check cache first
	ip := e.safeSearch.GetCachedIP(redirectDomain)
	if ip == nil {
		// Grab resolver snapshot under lock to avoid nil dereference during shutdown
		e.mu.Lock()
		resolver := e.resolver
		e.mu.Unlock()
		if resolver == nil {
			return false
		}

		// Lazy resolve
		var err error
		ip, err = resolver.ResolveARecord(redirectDomain, e.primaryDNS)
		if err != nil {
			logf("SafeSearch resolve failed for %s: %v", redirectDomain, err)
			return false
		}
		e.safeSearch.CacheIP(redirectDomain, ip)
		logf("SafeSearch resolved: %s → %s", redirectDomain, ip.String())
	}

	response := BuildRedirectResponse(queryInfo, ip)
	e.writeToTUN(response)
	e.totalQueries.Add(1)

	elapsed := time.Since(startTime).Milliseconds()
	e.notifyLog(queryInfo.Domain, false, queryInfo.QueryType, elapsed, appName, ip.String(), "")
	return true
}

// handleFirewallBlock handles a DNS query blocked by the per-app firewall.
func (e *Engine) handleFirewallBlock(queryInfo *DNSQueryInfo, appName string, startTime time.Time) {
	var response []byte
	switch e.responseType {
	case ResponseNXDomain:
		response = BuildNXDomainResponse(queryInfo)
	case ResponseRefused:
		response = BuildRefusedResponse(queryInfo)
	default:
		response = BuildBlockedResponse(queryInfo)
	}

	e.writeToTUN(response)
	e.totalQueries.Add(1)
	e.blockedQueries.Add(1)

	elapsed := time.Since(startTime).Milliseconds()
	logf("BLOCKED: %s (by: firewall, app: %s)", queryInfo.Domain, appName)
	e.notifyLog(queryInfo.Domain, true, queryInfo.QueryType, elapsed, appName, "", "firewall")
}

// handleBlockedDomain handles a blocked domain.
func (e *Engine) handleBlockedDomain(queryInfo *DNSQueryInfo, blockedBy, appName string, startTime time.Time) {
	var response []byte
	switch e.responseType {
	case ResponseNXDomain:
		response = BuildNXDomainResponse(queryInfo)
	case ResponseRefused:
		response = BuildRefusedResponse(queryInfo)
	default:
		response = BuildBlockedResponse(queryInfo)
	}

	e.writeToTUN(response)
	e.totalQueries.Add(1)
	e.blockedQueries.Add(1)

	elapsed := time.Since(startTime).Milliseconds()
	logf("BLOCKED: %s (by: %s, app: %s)", queryInfo.Domain, blockedBy, appName)
	e.notifyLog(queryInfo.Domain, true, queryInfo.QueryType, elapsed, appName, "", blockedBy)
}

// handleForward forwards a DNS query to upstream and writes the response.
func (e *Engine) handleForward(queryInfo *DNSQueryInfo, appName string, startTime time.Time) {
	// Grab resolver snapshot under lock to avoid nil dereference during shutdown
	e.mu.Lock()
	resolver := e.resolver
	e.mu.Unlock()
	if resolver == nil {
		// Engine is shutting down, drop the query silently
		return
	}

	resp, err := resolver.Resolve(queryInfo.RawDNSPayload)
	if err != nil {
		logf("DNS resolve failed for %s: %v", queryInfo.Domain, err)
		servfail := BuildServfailResponse(queryInfo)
		e.writeToTUN(servfail)
		e.totalQueries.Add(1)

		elapsed := time.Since(startTime).Milliseconds()
		e.notifyLog(queryInfo.Domain, false, queryInfo.QueryType, elapsed, appName, "", "")
		return
	}

	// Detect upstream DNS blocking (e.g., NextDNS/AdGuard DNS returning 0.0.0.0)
	if isUpstreamBlocked(resp) {
		response := BuildForwardedResponse(queryInfo, resp)
		e.writeToTUN(response)
		e.totalQueries.Add(1)
		e.blockedQueries.Add(1)

		elapsed := time.Since(startTime).Milliseconds()
		logf("BLOCKED: %s (by: upstream_dns, app: %s)", queryInfo.Domain, appName)
		e.notifyLog(queryInfo.Domain, true, queryInfo.QueryType, elapsed, appName, "", "upstream_dns")
		return
	}

	response := BuildForwardedResponse(queryInfo, resp)
	e.writeToTUN(response)
	e.totalQueries.Add(1)

	elapsed := time.Since(startTime).Milliseconds()
	e.notifyLog(queryInfo.Domain, false, queryInfo.QueryType, elapsed, appName, "", "")
}

// handleSplitDNSForward forwards a DNS query through the WireGuard tunnel
// for domains matching split-DNS zones. Instead of resolving via our own
// resolver (which can't reach the WireGuard DNS because the app is excluded
// from VPN), we forward the original packet through the router/WireGuard adapter.
func (e *Engine) handleSplitDNSForward(queryInfo *DNSQueryInfo, appName string, startTime time.Time) {
	// Build a packet addressed to the WireGuard DNS server
	// and inject it into the router (WireGuard adapter).
	// The router will encrypt it and send it through the tunnel.
	// The response comes back through channelTUN → real TUN → app.
	e.mu.Lock()
	router := e.router
	resolver := e.resolver
	e.mu.Unlock()

	if router == nil || resolver == nil {
		e.handleForward(queryInfo, appName, startTime)
		return
	}

	resolver.mu.RLock()
	splitDNS := resolver.splitDNS
	resolver.mu.RUnlock()

	if splitDNS == "" {
		e.handleForward(queryInfo, appName, startTime)
		return
	}

	// Build a new DNS packet destined for the WireGuard DNS server
	dnsServerIP := net.ParseIP(splitDNS)
	if dnsServerIP == nil {
		logf("Split-DNS: invalid DNS server IP: %s", splitDNS)
		e.handleForward(queryInfo, appName, startTime)
		return
	}

	// Construct IP/UDP packet with the original DNS payload
	// Source: original source IP/port, Dest: WireGuard DNS:53
	var packet []byte
	if queryInfo.IsIPv6 {
		packet = buildIPv6UDPPacket(queryInfo.SourceIP, dnsServerIP, queryInfo.SourcePort, 53, queryInfo.RawDNSPayload)
	} else {
		packet = buildIPv4UDPPacket(queryInfo.SourceIP, dnsServerIP.To4(), queryInfo.SourcePort, 53, queryInfo.RawDNSPayload)
	}

	// Forward through the WireGuard tunnel via the router
	router.RoutePacket(packet, len(packet))

	e.totalQueries.Add(1)
	elapsed := time.Since(startTime).Milliseconds()
	logf("Split-DNS: forwarded %s to %s via WireGuard", queryInfo.Domain, splitDNS)
	e.notifyLog(queryInfo.Domain, false, queryInfo.QueryType, elapsed, appName, "", "")
}

// isUpstreamBlocked checks if a DNS response indicates the domain was blocked
// by the upstream DNS server (e.g., NextDNS, AdGuard DNS, ControlD).
//
// Blocking DNS servers typically return 0.0.0.0 (A) or :: (AAAA) for blocked domains.
// We detect this by checking if ALL answer records contain null/zero IPs.
//
// To avoid false positives:
// - NXDOMAIN responses are NOT flagged (could be a typo like "googleee.com")
// - Empty responses (no answer section) are NOT flagged
// - Responses with a mix of null and real IPs are NOT flagged
func isUpstreamBlocked(rawResp []byte) bool {
	var msg dns.Msg
	if err := msg.Unpack(rawResp); err != nil {
		return false
	}

	// Must have answer records — empty or NXDOMAIN is not "blocked by upstream"
	if len(msg.Answer) == 0 {
		return false
	}

	// Check if ALL A/AAAA records are null IPs
	nullCount := 0
	ipRecordCount := 0

	for _, rr := range msg.Answer {
		switch r := rr.(type) {
		case *dns.A:
			ipRecordCount++
			if r.A.Equal(net.IPv4zero) {
				nullCount++
			}
		case *dns.AAAA:
			ipRecordCount++
			if r.AAAA.Equal(net.IPv6zero) {
				nullCount++
			}
		}
	}

	// Only flag if we found IP records and ALL of them are null
	return ipRecordCount > 0 && nullCount == ipRecordCount
}

// writeToTUN writes a packet to the TUN device.
func (e *Engine) writeToTUN(data []byte) {
	e.mu.Lock()
	f := e.tunFile
	e.mu.Unlock()

	if f == nil {
		return
	}
	if _, err := f.Write(data); err != nil {
		logf("TUN write error: %v", err)
	}
}

// notifyLog sends a DNS query event to the Kotlin callback.
func (e *Engine) notifyLog(domain string, blocked bool, queryType uint16, responseTimeMs int64, appName, resolvedIP, blockedBy string) {
	if e.logCallback != nil {
		e.logCallback.OnDNSQuery(domain, blocked, int(queryType), responseTimeMs, appName, resolvedIP, blockedBy)
	}
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
