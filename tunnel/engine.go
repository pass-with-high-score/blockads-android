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

	adTrie  *MmapTrie
	secTrie *MmapTrie

	// Bloom filters for fast pre-filtering (skip trie if definitely clean)
	adBloom  *BloomFilter
	secBloom *BloomFilter

	mu      sync.Mutex
	running bool
	tunFile *os.File

	// Pipeline components
	router      *Router
	interceptor *DnsInterceptor

	// MITM Proxy
	mitmProxy *MitmProxy

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
// It accepts the absolute paths to the ad/security binary trie files and their corresponding bloom filter files.
func (e *Engine) SetTries(adTriePath, secTriePath, adBloomPath, secBloomPath string) {
	e.mu.Lock()
	defer e.mu.Unlock()

	// Close old tries
	if e.adTrie != nil {
		e.adTrie.Close()
		e.adTrie = nil
	}
	if e.secTrie != nil {
		e.secTrie.Close()
		e.secTrie = nil
	}

	// Close old bloom filters
	if e.adBloom != nil {
		e.adBloom.Close()
		e.adBloom = nil
	}
	if e.secBloom != nil {
		e.secBloom.Close()
		e.secBloom = nil
	}

	// Load ad trie
	if adTriePath != "" {
		t, err := LoadMmapTrie(adTriePath)
		if err != nil {
			logf("Failed to load Ad Trie: %v", err)
		} else {
			e.adTrie = t
			logf("Loaded Ad Trie from Go native Mmap")
		}
	}

	// Load security trie
	if secTriePath != "" {
		t, err := LoadMmapTrie(secTriePath)
		if err != nil {
			logf("Failed to load Security Trie: %v", err)
		} else {
			e.secTrie = t
			logf("Loaded Security Trie from Go native Mmap")
		}
	}

	// Load ad bloom filter
	if adBloomPath != "" {
		bf, err := LoadBloomFilter(adBloomPath)
		if err != nil {
			logf("Failed to load Ad Bloom Filter: %v", err)
		} else {
			e.adBloom = bf
			logf("Loaded Ad Bloom Filter for fast pre-filtering")
		}
	}

	// Load security bloom filter
	if secBloomPath != "" {
		bf, err := LoadBloomFilter(secBloomPath)
		if err != nil {
			logf("Failed to load Security Bloom Filter: %v", err)
		} else {
			e.secBloom = bf
			logf("Loaded Security Bloom Filter for fast pre-filtering")
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
				wgAdapter, err := NewWgOutbound(tunDevice, ipcConfig)
				if err != nil {
					logf("WireGuard adapter create error: %v", err)
				} else {
					if err := wgAdapter.Start(); err != nil {
						logf("WireGuard adapter start error: %v", err)
					} else {
						e.router.SetAdapter(wgAdapter)
						logf("WireGuard adapter fully initialized and active")
					}
				}
			}
		}
	} else {
		logf("No WireGuard config, running in DNS-only mode")
	}

	// ── Packet Read Loop ─────────────────────────────────────────────
	// DnsInterceptor reads from TUN, routes DNS to adblock engine,
	// and non-DNS to Router → active OutboundAdapter.
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

	// Grab MITM proxy reference and clear it while locked
	proxy := e.mitmProxy
	e.mitmProxy = nil

	// Close TUN, shutdown resolver, clear caches — all while locked
	if e.tunFile != nil {
		e.tunFile.Close()
		e.tunFile = nil
	}
	if e.resolver != nil {
		e.resolver.Shutdown()
	}
	e.safeSearch.ClearCache()

	if e.adTrie != nil {
		e.adTrie.Close()
		e.adTrie = nil
	}
	if e.secTrie != nil {
		e.secTrie.Close()
		e.secTrie = nil
	}
	if e.adBloom != nil {
		e.adBloom.Close()
		e.adBloom = nil
	}
	if e.secBloom != nil {
		e.secBloom.Close()
		e.secBloom = nil
	}

	e.mu.Unlock()

	// Stop proxy OUTSIDE the lock (proxy.Stop() may block briefly)
	if proxy != nil {
		proxy.Stop()
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

// ── MITM Proxy API ───────────────────────────────────────────────────────────
// gomobile-compatible methods for controlling the HTTPS MITM popup blocker.

// StartMitmProxy starts the local HTTPS MITM proxy on the given address
// (e.g., "127.0.0.1:8080"). certDir is the persistent directory for the
// Root CA files (e.g., Android's getFilesDir()). Returns the PEM-encoded
// Root CA certificate that the user must install on their Android device.
//
// Returns empty string on error (check logs).
func (e *Engine) StartMitmProxy(addr string, certDir string) string {
	e.mu.Lock()
	if e.mitmProxy != nil {
		e.mu.Unlock()
		logf("MITM Proxy already running")
		return e.mitmProxy.GetCACertPEM()
	}

	proxy, err := NewMitmProxy(certDir)
	if err != nil {
		e.mu.Unlock()
		logf("Failed to create MITM proxy: %v", err)
		return ""
	}
	// ── Wire ad-block engine into proxy for Gate 1 blocking ──
	proxy.SetAdBlockChecker(e)
	e.mitmProxy = proxy
	e.mu.Unlock()

	caPEM := proxy.GetCACertPEM()
	logf("MITM Proxy: CA cert generated (%d bytes)", len(caPEM))

	// Bind the listener SYNCHRONOUSLY so the port is open before we return.
	// This guarantees the VPN can safely route traffic to this address.
	if err := proxy.Listen(addr); err != nil {
		e.mu.Lock()
		e.mitmProxy = nil
		e.mu.Unlock()
		logf("MITM Proxy listen error: %v", err)
		return ""
	}

	// Accept loop runs in background (Serve() blocks)
	go func() {
		if err := proxy.Serve(); err != nil {
			logf("MITM Proxy serve error: %v", err)
		}
	}()

	return caPEM
}

// StopMitmProxy stops the MITM proxy if it is running.
func (e *Engine) StopMitmProxy() {
	e.mu.Lock()
	proxy := e.mitmProxy
	e.mitmProxy = nil
	e.mu.Unlock()

	if proxy != nil {
		proxy.Stop()
	}
}

// GetMitmCACert returns the PEM-encoded Root CA certificate.
// certDir is the persistent directory where the CA files are stored.
// If the proxy is running in-memory, it returns its cert. Otherwise it reads from disk.
// Returns empty string if no CA cert exists.
func (e *Engine) GetMitmCACert(certDir string) string {
	e.mu.Lock()
	proxy := e.mitmProxy
	e.mu.Unlock()

	// If proxy is active, it has the cert in memory
	if proxy != nil {
		return proxy.GetCACertPEM()
	}

	// Proxy not running in this instance, check disk directly
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
	proxy := e.mitmProxy
	e.mu.Unlock()

	if proxy == nil {
		logf("MITM: SetAllowedUIDs called but proxy not running")
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

	proxy.GetFilter().SetAllowedUIDs(uids)
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
	secBloom := e.secBloom
	secTrie := e.secTrie
	adBloom := e.adBloom
	adTrie := e.adTrie
	e.mu.Unlock()

	if secTrie != nil {
		if secBloom == nil || secBloom.MightContainDomainOrParent(host) {
			if secTrie.ContainsOrParent(host) {
				return true
			}
		}
	}

	// ── Ad trie (Bloom pre-filter → Mmap Trie) ──
	if adTrie != nil {
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
	startTime := time.Now()
	domain := strings.ToLower(queryInfo.Domain)

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

	// Security domains
	if e.secTrie != nil {
		// If bloom filter exists and says "definitely clean", skip trie
		if e.secBloom == nil || e.secBloom.MightContainDomainOrParent(domain) {
			if e.secTrie.ContainsOrParent(domain) {
				e.handleBlockedDomain(queryInfo, "security", appName, startTime)
				return
			}
		}
	}

	// Ad domains
	if e.adTrie != nil {
		if e.adBloom == nil || e.adBloom.MightContainDomainOrParent(domain) {
			if e.adTrie.ContainsOrParent(domain) {
				e.handleBlockedDomain(queryInfo, "filter_list", appName, startTime)
				return
			}
		}
	}

	// Forward to upstream DNS
	e.handleForward(queryInfo, appName, startTime)
}

// handleSafeSearchRedirect handles a SafeSearch/YouTube redirect.
func (e *Engine) handleSafeSearchRedirect(queryInfo *DNSQueryInfo, redirectDomain, appName string, startTime time.Time) bool {
	// Check cache first
	ip := e.safeSearch.GetCachedIP(redirectDomain)
	if ip == nil {
		// Lazy resolve
		var err error
		ip, err = e.resolver.ResolveARecord(redirectDomain, e.primaryDNS)
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
	resp, err := e.resolver.Resolve(queryInfo.RawDNSPayload)
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
