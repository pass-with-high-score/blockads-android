package config

import (
	"net"
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

	cfgSingle, err := ParseRuleset("[filter_local]\nip-cidr, 1.1.1.1, reject\nfinal, direct\n")
	if err != nil {
		t.Fatalf("ParseRuleset failed: %v", err)
	}
	matcherSingle := NewMatcher(cfgSingle)
	if p, _ := matcherSingle.MatchNetIP("", net.ParseIP("1.1.1.1")); p != PolicyReject {
		t.Errorf("Expected REJECT for 1.1.1.1, got %s", p)
	}
	if p, _ := matcherSingle.MatchNetIP("1.1.1.1", net.ParseIP("1.1.1.1")); p != PolicyReject {
		t.Errorf("Expected REJECT for 1.1.1.1 with domain, got %s", p)
	}
}

func TestFinalFallbackWithSubsequentRule(t *testing.T) {
	snippet := `
[filter_local]
FINAL, DIRECT
HOST-KEYWORD, pwhs, REJECT
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}

	matcher := NewMatcher(cfg)

	// pwhs.app must match HOST-KEYWORD, pwhs, REJECT even though FINAL, DIRECT came first
	policy, matched := matcher.Match("pwhs.app", "")
	if policy != PolicyReject {
		t.Errorf("Expected REJECT for pwhs.app, got (%s, %s)", policy, matched)
	}
	if matched != "pwhs" {
		t.Errorf("Expected matched rule 'pwhs', got %s", matched)
	}

	// unmatched domain should fall through to FINAL with PolicyDirect
	policy, matched = matcher.Match("google.com", "")
	if policy != PolicyDirect {
		t.Errorf("Expected DIRECT for google.com, got (%s, %s)", policy, matched)
	}
	if matched != "FINAL" {
		t.Errorf("Expected matched rule 'FINAL', got %s", matched)
	}
}

func TestRulesetCaseInsensitivePolicy(t *testing.T) {
	content := "[filter_local]\nhost-keyword, pwhs, reject\nfinal, direct\n"
	cfg, err := ParseRuleset(content)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}
	matcher := NewMatcher(cfg)
	policy, matched := matcher.MatchNetIP("pwhs.app", nil)
	if policy != PolicyReject || matched != "pwhs" {
		t.Fatalf("Expected REJECT, pwhs, got %s, %s", policy, matched)
	}
}

func TestGeoIPMatching(t *testing.T) {
	snippet := `
[filter_local]
GEOIP, VN, REJECT
FINAL, DIRECT
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}

	matcher := NewMatcher(cfg)
	matcher.SetGeoIPLookup(func(ip net.IP) string {
		if ip.String() == "14.225.254.1" {
			return "VN"
		}
		if ip.String() == "1.1.1.1" {
			return "US"
		}
		return ""
	})

	policy, matched := matcher.MatchNetIP("vn-server", net.ParseIP("14.225.254.1"))
	if policy != PolicyReject || matched != "VN" {
		t.Fatalf("Expected REJECT, VN for 14.225.254.1, got %s, %s", policy, matched)
	}

	policy, matched = matcher.MatchNetIP("us-server", net.ParseIP("1.1.1.1"))
	if policy != PolicyDirect || matched != "FINAL" {
		t.Fatalf("Expected DIRECT, FINAL for 1.1.1.1, got %s, %s", policy, matched)
	}
}

func TestUserConfigCase(t *testing.T) {
	snippet := `
[filter_local]
ip-cidr, 10.0.0.0/8, direct
ip-cidr, 172.16.0.0/12, direct
ip-cidr, 192.168.0.0/16, direct
ip-cidr6, 2606.4700.4700:1111/128, reject
geoip, vn, reject
geoip, sg, reject
geoip, us, reject
final, direct
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}

	matcher := NewMatcher(cfg)
	matcher.SetGeoIPLookup(func(ip net.IP) string {
		if ip.String() == "111.65.242.20" || ip.String() == "111.65.250.2" {
			return "VN"
		}
		return ""
	})

	ip := net.ParseIP("111.65.242.20")
	policy, matched := matcher.MatchNetIP("111.65.242.20", ip)
	t.Logf("MatchNetIP(111.65.242.20): policy=%s, matched=%s", policy, matched)
	if policy != PolicyReject {
		t.Fatalf("Expected REJECT, got %s, %s", policy, matched)
	}
}

func BenchmarkMatchNetIP_GeoIP(b *testing.B) {
	snippet := `
ip-cidr, 10.0.0.0/8, direct
ip-cidr, 172.16.0.0/12, direct
ip-cidr, 192.168.0.0/16, direct
geoip, vn, reject
geoip, sg, reject
geoip, us, reject
final, direct
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		b.Fatalf("Parse failed: %v", err)
	}

	matcher := NewMatcher(cfg)
	matcher.SetGeoIPLookup(func(ip net.IP) string {
		return "VN"
	})

	ip := net.ParseIP("111.65.242.20")
	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		_, _ = matcher.MatchNetIP("111.65.242.20", ip)
	}
}

func TestDNSExclusionList(t *testing.T) {
	snippet := `
[general]
dns_exclusion_list = *.local, localhost, *.lan, custom.bank.vn

[filter_local]
host-suffix, local, reject
host-suffix, bank.vn, reject
final, reject
`
	cfg, err := ParseRuleset(snippet)
	if err != nil {
		t.Fatalf("ParseRuleset failed: %v", err)
	}

	if len(cfg.DNSExclusionList) != 4 {
		t.Fatalf("Expected 4 exclusions, got %d: %v", len(cfg.DNSExclusionList), cfg.DNSExclusionList)
	}

	matcher := NewMatcher(cfg)

	// *.local should bypass reject
	policy, matched := matcher.Match("router.local", "")
	if policy != PolicyDirect || matched != "dns_exclusion_list:*.local" {
		t.Errorf("router.local: got %s (%s), want DIRECT", policy, matched)
	}

	// localhost should bypass reject
	policy, matched = matcher.Match("localhost", "")
	if policy != PolicyDirect || matched != "dns_exclusion_list:localhost" {
		t.Errorf("localhost: got %s (%s), want DIRECT", policy, matched)
	}

	// *.lan should bypass reject
	policy, matched = matcher.Match("nas.lan", "")
	if policy != PolicyDirect || matched != "dns_exclusion_list:*.lan" {
		t.Errorf("nas.lan: got %s (%s), want DIRECT", policy, matched)
	}

	// custom.bank.vn should bypass reject
	policy, matched = matcher.Match("custom.bank.vn", "")
	if policy != PolicyDirect || matched != "dns_exclusion_list:custom.bank.vn" {
		t.Errorf("custom.bank.vn: got %s (%s), want DIRECT", policy, matched)
	}

	// other.bank.vn should still be REJECT
	policy, matched = matcher.Match("other.bank.vn", "")
	if policy != PolicyReject {
		t.Errorf("other.bank.vn: got %s (%s), want REJECT", policy, matched)
	}
}

func TestParseRuleSnippetAndAppend(t *testing.T) {
	snippet := `
# Remote adblock rules
HOST, ad.remote.com, REJECT
HOST-SUFFIX, tracking.remote.org, REJECT
IP-CIDR, 1.2.3.4/32, REJECT
`
	rules := ParseRuleSnippet(snippet)
	if len(rules) != 3 {
		t.Fatalf("Expected 3 parsed rules, got %d", len(rules))
	}

	baseCfg := &Config{FinalPolicy: PolicyDirect}
	matcher := NewMatcher(baseCfg)
	if matcher.RulesCount() != 0 {
		t.Fatalf("Expected 0 initial rules, got %d", matcher.RulesCount())
	}

	matcher.AppendRules(rules)
	if matcher.RulesCount() != 3 {
		t.Fatalf("Expected 3 rules after append, got %d", matcher.RulesCount())
	}

	policy, _ := matcher.Match("ad.remote.com", "")
	if policy != PolicyReject {
		t.Errorf("ad.remote.com: got %s, want REJECT", policy)
	}

	policy, _ = matcher.Match("test.tracking.remote.org", "")
	if policy != PolicyReject {
		t.Errorf("test.tracking.remote.org: got %s, want REJECT", policy)
	}
}

func TestIgnoredUnsupportedSections(t *testing.T) {
	configStr := `
[filter_local]
HOST, ad.com, REJECT

[rewrite_local]
^https?:\/\/example\.com url reject-200

[rewrite_remote]
https://example.com/rewrite.js, tag=Test

[task_local]
0 9 * * * task.js, tag=Cron

[http_backend]
server = 127.0.0.1:8080

[mitm]
hostname = *.google.com

[policy]
static = DIRECT, direct
`
	cfg, err := ParseRuleset(configStr)
	if err != nil {
		t.Fatalf("ParseRuleset failed: %v", err)
	}

	if len(cfg.Rules) != 1 {
		t.Errorf("Expected exactly 1 rule from filter_local, got %d: %+v", len(cfg.Rules), cfg.Rules)
	}
	if cfg.Rules[0].Value != "ad.com" {
		t.Errorf("Expected ad.com rule, got %s", cfg.Rules[0].Value)
	}
}

