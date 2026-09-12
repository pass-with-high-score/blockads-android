package tunnel

import (
	"fmt"
	"net"
	"strings"
	"time"

	"github.com/miekg/dns"
)

// ── Standalone DNS Server (Root/Proxy Mode) ──────────────────────────────────

// ServeDNS handles incoming DNS queries directly from a socket (no TUN fd).
func (e *Engine) ServeDNS(w dns.ResponseWriter, r *dns.Msg) {
	e.serveDNS(w, r, "")
}

// serveDNS is the implementation. appOverride, when non-empty, is used as
// the logged app name (full-tunnel passes the UID-resolved package here so
// DNS is attributed to the real app instead of the root-mode "RootProxy").
func (e *Engine) serveDNS(w dns.ResponseWriter, r *dns.Msg, appOverride string) {
	startTime := time.Now()
	if len(r.Question) == 0 {
		return
	}

	domain := strings.ToLower(r.Question[0].Name)
	domain = strings.TrimSuffix(domain, ".")
	queryType := r.Question[0].Qtype

	// Local asset host: synthesize a response with a routable IP from
	// the RFC 5737 documentation range so the browser can SYN to it
	// and have the packet enter our TUN.
	if domain == LocalAssetHost {
		m := new(dns.Msg)
		m.SetReply(r)
		if queryType == dns.TypeA {
			rr, _ := dns.NewRR(fmt.Sprintf("%s 300 IN A %s", r.Question[0].Name, localAssetSynthIP.String()))
			m.Answer = append(m.Answer, rr)
		} else if queryType == dns.TypeAAAA {
			// No IPv6 for local asset host; return empty NOERROR
		}
		_ = w.WriteMsg(m)
		e.totalQueries.Add(1)
		return
	}

	appName := "RootProxy"
	// Try to resolve the real app name from the source port of the incoming connection.
	// iptables REDIRECT preserves the original source port, so we can look up the UID
	// in /proc/net/udp by matching that port.
	if appOverride != "" {
		appName = appOverride
	} else if e.appResolver != nil {
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

	// DoH Bypass Protection (Issue #145): block DoH bootstrap queries so clients fall back to plaintext DNS
	if e.isDoHDomain(domain) {
		e.standaloneBlock(w, r, "doh_bypass_protection", appName, startTime)
		return
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
				if i < len(e.secTrieIDs) {
					id = e.secTrieIDs[i]
				}
				matchedIDs = append(matchedIDs, id)
			}
		}
	}

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
				if i < len(e.adTrieIDs) {
					id = e.adTrieIDs[i]
				}
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
