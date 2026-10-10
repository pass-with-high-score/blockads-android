package config

import (
	"testing"
)

const sampleRulesetConfig = `
; Sample Ruleset Configuration
[general]
geo_location_checker=http://www.google.com/generate_204
dns_exclusion_list=*.local, localhost

[dns]
server = 1.1.1.1
server = 8.8.8.8
server = https://dns.google/dns-query

[filter_local]
; Ads blocking
HOST, tracking.example.com, REJECT
HOST-SUFFIX, doubleclick.net, REJECT
HOST-KEYWORD, analytics, REJECT

; Local & IP routing
IP-CIDR, 192.168.0.0/16, DIRECT, no-resolve
IP-CIDR6, 2001:db8::/32, DIRECT

; Proxy rules
HOST-SUFFIX, google.com, PROXY
USER-AGENT, *Spotify*, PROXY

; Fallback
FINAL, DIRECT

[filter_remote]
https://raw.githubusercontent.com/filters/adblock.list, tag=AdBlock, update-interval=24, opt-parser=true
`

func TestParseRuleset(t *testing.T) {
	cfg, err := ParseRuleset(sampleRulesetConfig)
	if err != nil {
		t.Fatalf("ParseRuleset failed: %v", err)
	}

	if len(cfg.DNSServers) != 3 {
		t.Errorf("Expected 3 DNS servers, got %d: %v", len(cfg.DNSServers), cfg.DNSServers)
	}

	if cfg.General["geo_location_checker"] != "http://www.google.com/generate_204" {
		t.Errorf("Unexpected general setting: %v", cfg.General)
	}

	if len(cfg.RemoteFilters) != 1 {
		t.Fatalf("Expected 1 remote filter, got %d", len(cfg.RemoteFilters))
	}
	rf := cfg.RemoteFilters[0]
	if rf.Tag != "AdBlock" || rf.Interval != 24 || !rf.OptParser {
		t.Errorf("Unexpected remote filter parsed: %+v", rf)
	}

	if cfg.FinalPolicy != PolicyDirect {
		t.Errorf("Expected FinalPolicy DIRECT, got %s", cfg.FinalPolicy)
	}

	if len(cfg.Rules) != 8 {
		t.Errorf("Expected 8 rules, got %d", len(cfg.Rules))
	}
}

func TestMatcher(t *testing.T) {
	cfg, err := ParseRuleset(sampleRulesetConfig)
	if err != nil {
		t.Fatalf("ParseRuleset failed: %v", err)
	}

	matcher := NewMatcher(cfg)

	tests := []struct {
		domain   string
		ip       string
		expected string
	}{
		{"tracking.example.com", "", PolicyReject},
		{"sub.doubleclick.net", "", PolicyReject},
		{"doubleclick.net", "", PolicyReject},
		{"my-analytics-tracker.org", "", PolicyReject},
		{"www.google.com", "", "PROXY"},
		{"not-in-list.org", "", PolicyDirect}, // Fallback to FINAL
		{"", "192.168.1.10", PolicyDirect},
		{"", "2001:db8::1", PolicyDirect},
	}

	for _, tc := range tests {
		policy, matched := matcher.Match(tc.domain, tc.ip)
		if policy != tc.expected {
			t.Errorf("Match(%q, %q) = (%q, %q); expected policy %q",
				tc.domain, tc.ip, policy, matched, tc.expected)
		}
	}
}

func TestRuleOrderingPriority(t *testing.T) {
	snippet := `
HOST, special.google.com, REJECT
HOST-SUFFIX, google.com, DIRECT
FINAL, REJECT
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		t.Fatalf("ParseRuleset failed: %v", err)
	}

	matcher := NewMatcher(cfg)

	// special.google.com should match HOST rule first (REJECT) even though google.com suffix is DIRECT
	policy, _ := matcher.Match("special.google.com", "")
	if policy != PolicyReject {
		t.Errorf("Expected REJECT for special.google.com, got %s", policy)
	}

	// other.google.com should match HOST-SUFFIX (DIRECT)
	policy, _ = matcher.Match("other.google.com", "")
	if policy != PolicyDirect {
		t.Errorf("Expected DIRECT for other.google.com, got %s", policy)
	}

	// apple.com matches FINAL (REJECT)
	policy, _ = matcher.Match("apple.com", "")
	if policy != PolicyReject {
		t.Errorf("Expected REJECT for apple.com, got %s", policy)
	}
}

func TestRawSnippetWithoutHeaders(t *testing.T) {
	snippet := `
# A pure snippet without section headers
HOST, ad.server.com, REJECT
HOST-KEYWORD, banner, REJECT
FINAL, DIRECT
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}

	matcher := NewMatcher(cfg)
	if matcher.RulesCount() != 3 {
		t.Errorf("Expected 3 rules, got %d", matcher.RulesCount())
	}

	policy, _ := matcher.Match("ad.server.com", "")
	if policy != PolicyReject {
		t.Errorf("Expected REJECT, got %s", policy)
	}
}

func TestUserAgentMatching(t *testing.T) {
	cfg, err := ParseRuleset(sampleRulesetConfig)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}

	matcher := NewMatcher(cfg)

	policy, _ := matcher.MatchWithUA("api.music.com", "", "Spotify/8.6.0 Android")
	if policy != "PROXY" {
		t.Errorf("Expected PROXY for Spotify UA, got %s", policy)
	}
}

func TestEdgeCases(t *testing.T) {
	snippet := `
; Leading dots and uppercase
HOST-SUFFIX, .EXAMPLE.COM, REJECT
HOST, ADS.TRACK.ME., REJECT
IP-CIDR, 1.2.3.4, REJECT
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}

	matcher := NewMatcher(cfg)

	// Suffix with leading dot normalized
	policy, _ := matcher.Match("test.example.com", "")
	if policy != PolicyReject {
		t.Errorf("Expected REJECT for test.example.com, got %s", policy)
	}

	// Exact host with trailing dot and uppercase
	policy, _ = matcher.Match("ads.track.me", "")
	if policy != PolicyReject {
		t.Errorf("Expected REJECT for ads.track.me, got %s", policy)
	}

	// Single IP without CIDR mask
	policy, _ = matcher.Match("", "1.2.3.4")
	if policy != PolicyReject {
		t.Errorf("Expected REJECT for single IP 1.2.3.4, got %s", policy)
	}
}
