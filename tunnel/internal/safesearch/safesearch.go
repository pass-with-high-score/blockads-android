package safesearch

import (
	"net"
	"strings"
	"sync"
)

// SafeSearch manages SafeSearch and YouTube restricted mode enforcement.
type SafeSearch struct {
	mu sync.RWMutex

	enabled           bool
	youtubeRestricted bool

	// IP cache for SafeSearch domains (lazily resolved)
	ipCache map[string]net.IP
}

// SafeSearchRedirect maps domain patterns to their SafeSearch domain.
type SafeSearchRedirect struct {
	DomainPattern    string
	SafeSearchDomain string
}

var safeSearchRedirects = []SafeSearchRedirect{
	{DomainPattern: "google.", SafeSearchDomain: "forcesafesearch.google.com"},
	{DomainPattern: "bing.com", SafeSearchDomain: "strict.bing.com"},
}

var youtubeDomains = []string{
	"youtube.com",
	"youtube-nocookie.com",
	"youtube.googleapis.com",
	"youtubei.googleapis.com",
}

const youtubeRestrictDomain = "restrict.youtube.com"

// SafeSearchAction indicates what action to take.
type SafeSearchAction int

const (
	ActionNone     SafeSearchAction = iota
	ActionRedirect                  // Redirect to SafeSearch domain
)

// SafeSearchResult holds the result of a SafeSearch check.
type SafeSearchResult struct {
	Action         SafeSearchAction
	RedirectDomain string
}

// NewSafeSearch creates a new SafeSearch manager.
func NewSafeSearch() *SafeSearch {
	return &SafeSearch{
		ipCache: make(map[string]net.IP),
	}
}

// SetEnabled enables or disables SafeSearch.
func (s *SafeSearch) SetEnabled(enabled bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.enabled = enabled
}

// SetYouTubeRestricted enables or disables YouTube restricted mode.
func (s *SafeSearch) SetYouTubeRestricted(enabled bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.youtubeRestricted = enabled
}

// Check checks if a domain should be redirected for SafeSearch.
// Only applies to A (1) and AAAA (28) queries.
func (s *SafeSearch) Check(domain string, queryType uint16) SafeSearchResult {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if !s.enabled || (queryType != 1 && queryType != 28) {
		return SafeSearchResult{Action: ActionNone}
	}

	domain = strings.ToLower(domain)
	for _, redirect := range safeSearchRedirects {
		if matchesDomain(domain, redirect.DomainPattern) {
			return SafeSearchResult{
				Action:         ActionRedirect,
				RedirectDomain: redirect.SafeSearchDomain,
			}
		}
	}

	return SafeSearchResult{Action: ActionNone}
}

// CheckYouTube checks if a domain should be redirected for YouTube restricted mode.
// Only applies to A (1) and AAAA (28) queries.
func (s *SafeSearch) CheckYouTube(domain string, queryType uint16) (bool, string) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if !s.youtubeRestricted || (queryType != 1 && queryType != 28) {
		return false, ""
	}

	domain = strings.ToLower(domain)
	for _, ytDomain := range youtubeDomains {
		if domain == ytDomain || strings.HasSuffix(domain, "."+ytDomain) {
			return true, youtubeRestrictDomain
		}
	}

	return false, ""
}

// GetCachedIP returns a cached IP for a domain, or nil if not cached.
func (s *SafeSearch) GetCachedIP(domain string) net.IP {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.ipCache[domain]
}

// CacheIP caches an IP for a domain.
func (s *SafeSearch) CacheIP(domain string, ip net.IP) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ipCache[domain] = ip
}

// ClearCache clears the IP cache.
func (s *SafeSearch) ClearCache() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ipCache = make(map[string]net.IP)
}

// matchesDomain matches a domain against a pattern.
// Pattern "google." matches google.com, www.google.com, google.co.uk, www.google.co.uk.
// Pattern "bing.com" matches bing.com and www.bing.com.
func matchesDomain(domain, pattern string) bool {
	if strings.HasSuffix(pattern, ".") {
		// Pattern like "google." — match only search hostnames
		baseLabel := strings.TrimSuffix(pattern, ".")
		parts := strings.Split(domain, ".")
		if len(parts) == 0 {
			return false
		}
		idx := -1
		for i, p := range parts {
			if p == baseLabel {
				idx = i
				break
			}
		}
		if idx == -1 {
			return false
		}
		return idx == 0 || (idx == 1 && parts[0] == "www")
	}
	// Exact domain or subdomain match
	return domain == pattern || strings.HasSuffix(domain, "."+pattern)
}
