package tunnel

import (
	"strings"
	"sync"
)

// ─────────────────────────────────────────────────────────────────────────────
// MITM Smart Filter — Dynamic interception decisions.
//
// Three-layer decision engine (checked in order):
//   1. UID Check      → Only allowed UIDs (browsers) are candidates for MITM.
//   2. Auto-Blacklist → Domains that failed TLS handshake are permanently
//                       passed through (cert pinning detected).
//   3. SNI Keywords   → If the domain contains sensitive keywords (bank, pay,
//                       auth, etc.), pass through immediately.
//
// If all checks pass → Intercept (MITM + cosmetic CSS injection).
// Default for non-browser UIDs → Direct pass-through.
// ─────────────────────────────────────────────────────────────────────────────

// MitmFilter manages dynamic interception decisions.
type MitmFilter struct {
	mu sync.RWMutex

	// allowedUIDs contains the UIDs of apps we're allowed to MITM (browsers).
	// Key = UID (int32), stored as int for map efficiency.
	allowedUIDs map[int]bool

	// permanentBlacklist contains domains where TLS handshake failed
	// (cert pinning detected). These are auto-added and never MITM'd again.
	permanentBlacklist map[string]bool

	// extraPassthroughSuffixes is a runtime-loaded list (typically from
	// assets/https_passthrough.txt) of additional DNS suffixes that
	// should never be MITM'd. Stored with leading dot so the suffix
	// match works the same way as the hardcoded list.
	extraPassthroughSuffixes []string
}

// sniSensitiveKeywords — if a domain contains any of these, NEVER intercept.
// This catches banking, authentication, and payment services dynamically
// without needing to maintain a huge hardcoded domain list.
var sniSensitiveKeywords = []string{
	"bank",
	"pay",
	"payment",
	"auth",
	"oauth",
	"login",
	"signin",
	"token",
	"secure",
	"wallet",
	"crypto",
	"trading",
	"invest",
	"finance",
	"insurance",
	"healthcare",
	"medical",
	"gov",
}

// minimalPassthroughSuffixes — absolute minimum hardcoded list for domains
// with aggressive cert pinning that ALWAYS break under MITM.
// Kept tiny — the SNI keywords + auto-blacklist handle 90% of cases.
var minimalPassthroughSuffixes = []string{
	// Google ecosystem (aggressive HPKP / cert transparency)
	".google.com",
	".googleapis.com",
	".gstatic.com",
	".android.com",
	".youtube.com",
	".googlevideo.com",
	".googleusercontent.com",
	// Apple
	".apple.com",
	".icloud.com",
	// Meta
	".facebook.com",
	".whatsapp.com",
	".instagram.com",
	".fbcdn.net",
	// Microsoft / GitHub
	".github.com",
	".githubusercontent.com",
	".githubassets.com",
	".github.io",
	".microsoft.com",
	".live.com",
	".microsoftonline.com",
	// Amazon / AWS
	".amazonaws.com",
	".cloudfront.net",
	// Firebase / crash reporting
	".firebaseio.com",
	".crashlytics.com",
	".app-measurement.com",
	// CDN / Infrastructure (cert pinning common)
	".cloudflare.com",
	".akamaized.net",
	".fastly.net",
	// Sentry (our crash reporting)
	".sentry.io",
	".ingest.sentry.io",
}

// NewMitmFilter creates a new filter with no allowed UIDs.
func NewMitmFilter() *MitmFilter {
	return &MitmFilter{
		allowedUIDs:        make(map[int]bool),
		permanentBlacklist: make(map[string]bool),
	}
}

// SetAllowedUIDs replaces the set of UIDs allowed for MITM interception.
// Called from Kotlin with browser UIDs.
func (f *MitmFilter) SetAllowedUIDs(uids []int) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.allowedUIDs = make(map[int]bool, len(uids))
	for _, uid := range uids {
		f.allowedUIDs[uid] = true
	}
	logf("MITM Filter: updated allowed UIDs (%d apps)", len(uids))
}

// IsUIDAllowed checks if a UID is in the allowed set (i.e., is a browser).
func (f *MitmFilter) IsUIDAllowed(uid int) bool {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.allowedUIDs[uid]
}

// HasAllowedUIDs returns true if any browser UIDs have been configured.
// When true and we can't determine the source UID (Android 10+ SELinux),
// we default to pass-through rather than MITM-ing unknown traffic.
func (f *MitmFilter) HasAllowedUIDs() bool {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return len(f.allowedUIDs) > 0
}

// SetExtraPassthroughSuffixes replaces the runtime-loaded passthrough
// list. Each entry should be a bare DNS suffix without a leading dot
// (e.g., "vietcombank.com.vn") — this method normalises by lowercasing
// and prepending a dot so suffix matching works the same as the
// hardcoded list. Empty entries and entries that look like comments
// are dropped.
func (f *MitmFilter) SetExtraPassthroughSuffixes(suffixes []string) {
	clean := make([]string, 0, len(suffixes))
	for _, s := range suffixes {
		s = strings.TrimSpace(strings.ToLower(s))
		if s == "" || s[0] == '#' || strings.HasPrefix(s, "//") {
			continue
		}
		// Normalise: ensure leading dot so HasSuffix matches subdomains.
		if !strings.HasPrefix(s, ".") {
			s = "." + s
		}
		clean = append(clean, s)
	}
	f.mu.Lock()
	f.extraPassthroughSuffixes = clean
	f.mu.Unlock()
	logf("MITM Filter: loaded %d extra passthrough suffixes", len(clean))
}

// IsInterceptionAllowed determines if a domain should be MITM'd.
// This is the SECOND check (after UID). Only called for browser traffic.
//
// Returns true  → Intercept (decrypt TLS, inject cosmetic CSS).
// Returns false → Forward directly (no decryption).
func (f *MitmFilter) IsInterceptionAllowed(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))

	// Strip port if present
	if idx := strings.LastIndex(host, ":"); idx != -1 {
		host = host[:idx]
	}

	// Layer 1: Check auto-blacklist (domains with cert pinning)
	f.mu.RLock()
	blacklisted := f.permanentBlacklist[host]
	f.mu.RUnlock()
	if blacklisted {
		return false
	}

	// Layer 2: Check minimal hardcoded passthrough
	for _, suffix := range minimalPassthroughSuffixes {
		if strings.HasSuffix(host, suffix) || host == suffix[1:] {
			return false
		}
	}

	// Layer 2b: Check runtime-loaded passthrough (assets/HttpsExclusions)
	f.mu.RLock()
	extra := f.extraPassthroughSuffixes
	f.mu.RUnlock()
	for _, suffix := range extra {
		if strings.HasSuffix(host, suffix) || host == suffix[1:] {
			return false
		}
	}

	// Layer 3: SNI sensitive keyword scan
	for _, keyword := range sniSensitiveKeywords {
		if strings.Contains(host, keyword) {
			return false
		}
	}

	// Layer 4: IP addresses → never intercept
	if isIPAddress(host) {
		return false
	}

	// All checks passed → intercept this domain
	return true
}

// BlacklistDomain permanently adds a domain to the passthrough cache.
// Called when a TLS handshake fails (cert pinning detected).
func (f *MitmFilter) BlacklistDomain(host string) {
	host = strings.ToLower(strings.TrimSpace(host))
	f.mu.Lock()
	f.permanentBlacklist[host] = true
	f.mu.Unlock()
	logf("MITM Filter: auto-blacklisted '%s' (cert pinning detected)", host)
}

// GetBlacklistCount returns the number of auto-blacklisted domains.
func (f *MitmFilter) GetBlacklistCount() int {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return len(f.permanentBlacklist)
}

// isIPAddress checks if a string looks like an IP address (v4 or v6).
func isIPAddress(host string) bool {
	// IPv6
	if strings.Contains(host, ":") {
		return true
	}
	// IPv4: all characters are digits and dots
	for _, c := range host {
		if c != '.' && (c < '0' || c > '9') {
			return false
		}
	}
	return len(host) > 0
}
