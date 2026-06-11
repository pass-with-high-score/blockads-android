package tunnel

import (
	"strings"
	"sync"
)

// Blocker handles domain blocking with priority-based rules.
// Priority order: custom allow > custom block > whitelist > security > ad blocklist.
type Blocker struct {
	mu sync.RWMutex

	// Ad domains (main blocklist)
	adDomains map[string]struct{}
	// Security domains (malware/phishing)
	securityDomains map[string]struct{}
	// Whitelisted domains (always allowed)
	whitelistedDomains map[string]struct{}
	// Custom allow rules (@@||example.com^)
	customAllowDomains map[string]struct{}
	// Custom block rules (||example.com^)
	customBlockDomains map[string]struct{}
	// Wildcard rules (e.g., *.ads.example.com)
	wildcardBlocks    []string
	wildcardAllows    []string
	wildcardWhitelist []string
}

// BlockResult holds the result of a domain check.
type BlockResult struct {
	Blocked   bool
	BlockedBy string // "ad", "security", "custom", "firewall", or ""
}

// NewBlocker creates a new Blocker instance.
func NewBlocker() *Blocker {
	return &Blocker{
		adDomains:          make(map[string]struct{}),
		securityDomains:    make(map[string]struct{}),
		whitelistedDomains: make(map[string]struct{}),
		customAllowDomains: make(map[string]struct{}),
		customBlockDomains: make(map[string]struct{}),
	}
}

// LoadDomains loads ad domains from newline-separated data.
func (b *Blocker) LoadDomains(data string) int {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.adDomains = make(map[string]struct{})
	count := 0
	for _, line := range strings.Split(data, "\n") {
		domain := strings.TrimSpace(strings.ToLower(line))
		if domain == "" || strings.HasPrefix(domain, "#") {
			continue
		}
		b.adDomains[domain] = struct{}{}
		count++
	}
	return count
}

// LoadSecurityDomains loads security (malware/phishing) domains.
func (b *Blocker) LoadSecurityDomains(data string) int {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.securityDomains = make(map[string]struct{})
	count := 0
	for _, line := range strings.Split(data, "\n") {
		domain := strings.TrimSpace(strings.ToLower(line))
		if domain == "" || strings.HasPrefix(domain, "#") {
			continue
		}
		b.securityDomains[domain] = struct{}{}
		count++
	}
	return count
}

// LoadWhitelist loads whitelisted domains.
// Entries with a "*." prefix are treated as wildcard suffix patterns
// (e.g., "*.example.com" matches "x.example.com" but not "example.com").
func (b *Blocker) LoadWhitelist(data string) {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.whitelistedDomains = make(map[string]struct{})
	b.wildcardWhitelist = nil
	for _, line := range strings.Split(data, "\n") {
		domain := strings.TrimSpace(strings.ToLower(line))
		if domain == "" {
			continue
		}
		if strings.HasPrefix(domain, "*.") {
			b.wildcardWhitelist = append(b.wildcardWhitelist, domain[2:])
		} else {
			b.whitelistedDomains[domain] = struct{}{}
		}
	}
}

// LoadCustomRules loads custom allow and block rules.
func (b *Blocker) LoadCustomRules(allowData, blockData string) {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.customAllowDomains = make(map[string]struct{})
	b.wildcardAllows = nil
	for _, line := range strings.Split(allowData, "\n") {
		domain := strings.TrimSpace(strings.ToLower(line))
		if domain == "" {
			continue
		}
		if strings.HasPrefix(domain, "*.") {
			b.wildcardAllows = append(b.wildcardAllows, domain[2:]) // store without *. prefix
		} else {
			b.customAllowDomains[domain] = struct{}{}
		}
	}

	b.customBlockDomains = make(map[string]struct{})
	b.wildcardBlocks = nil
	for _, line := range strings.Split(blockData, "\n") {
		domain := strings.TrimSpace(strings.ToLower(line))
		if domain == "" {
			continue
		}
		if strings.HasPrefix(domain, "*.") {
			b.wildcardBlocks = append(b.wildcardBlocks, domain[2:]) // store without *. prefix
		} else {
			b.customBlockDomains[domain] = struct{}{}
		}
	}
}

// IsBlocked checks if a domain should be blocked.
// Returns the block result with reason.
func (b *Blocker) IsBlocked(domain string) BlockResult {
	b.mu.RLock()
	defer b.mu.RUnlock()

	domain = strings.ToLower(domain)

	// Priority 1: Custom allow rules
	if b.checkDomainAndParents(domain, b.customAllowDomains) || b.matchesWildcard(domain, b.wildcardAllows) {
		return BlockResult{Blocked: false}
	}

	// Priority 2: Custom block rules
	if b.checkDomainAndParents(domain, b.customBlockDomains) || b.matchesWildcard(domain, b.wildcardBlocks) {
		return BlockResult{Blocked: true, BlockedBy: "custom"}
	}

	// Priority 3: Whitelist
	if b.checkDomainAndParents(domain, b.whitelistedDomains) || b.matchesWildcard(domain, b.wildcardWhitelist) {
		return BlockResult{Blocked: false}
	}

	// Priority 4: Security domains (malware/phishing)
	if b.checkDomainAndParents(domain, b.securityDomains) {
		return BlockResult{Blocked: true, BlockedBy: "security"}
	}

	// Priority 5: Ad domains
	if b.checkDomainAndParents(domain, b.adDomains) {
		return BlockResult{Blocked: true, BlockedBy: "ad"}
	}

	return BlockResult{Blocked: false}
}

// checkDomainAndParents checks if a domain or any of its parent domains
// exists in the given set. For example, for "sub.ads.google.com" it checks:
// sub.ads.google.com → ads.google.com → google.com → com
func (b *Blocker) checkDomainAndParents(domain string, set map[string]struct{}) bool {
	d := domain
	for {
		if _, ok := set[d]; ok {
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

// matchesWildcard checks if domain matches any wildcard pattern.
// Wildcard patterns like "*.ads.example.com" match "x.ads.example.com"
// but NOT "ads.example.com" itself.
func (b *Blocker) matchesWildcard(domain string, patterns []string) bool {
	for _, suffix := range patterns {
		if strings.HasSuffix(domain, "."+suffix) && domain != suffix {
			return true
		}
	}
	return false
}
