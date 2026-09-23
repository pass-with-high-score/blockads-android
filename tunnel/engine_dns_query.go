package tunnel

import (
	"net"
	"strings"
	"time"

	"github.com/miekg/dns"
)

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
		splitDNS := resolver.SplitDNS()
		splitZones := resolver.SplitZones()

		if splitDNS != "" && len(splitZones) > 0 && resolver.MatchesSplitZone(domain) {
			// Route this DNS packet through the WireGuard tunnel via the router
			e.handleSplitDNSForward(queryInfo, appName, startTime)
			return
		}
	}

	// DoH Bypass Protection (Issue #145): block DoH bootstrap queries so clients fall back to plaintext DNS
	if e.isDoHDomain(domain) {
		e.handleBlockedDomain(queryInfo, "doh_bypass_protection", appName, startTime)
		return
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
		if secTrie == nil {
			continue
		}
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
		if adTrie == nil {
			continue
		}
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
func (e *Engine) handleSafeSearchRedirect(queryInfo *DNSQueryInfo, redirectDomain string, appName string, startTime time.Time) bool {
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

	splitDNS := resolver.SplitDNS()
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

// notifyLog sends a DNS query event to the Kotlin callback.
func (e *Engine) notifyLog(domain string, blocked bool, queryType uint16, responseTimeMs int64, appName, resolvedIP, blockedBy string) {
	if e.logCallback != nil {
		e.logCallback.OnDNSQuery(domain, blocked, int(queryType), responseTimeMs, appName, resolvedIP, blockedBy)
	}
}
