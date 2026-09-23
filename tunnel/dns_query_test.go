package tunnel

import (
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/miekg/dns"
)

// defaultUpstream answers A with 192.0.2.1 and AAAA with 2001:db8::1.
func defaultUpstream(r *dns.Msg) *dns.Msg {
	if r.Question[0].Qtype == dns.TypeAAAA {
		return answerWith("AAAA 2001:db8::1")(r)
	}
	return answerWith("A 192.0.2.1")(r)
}

type pipelineCase struct {
	name     string
	domain   string
	qtype    uint16
	setup    func(t *testing.T, h *tunHarness, up *upstream)
	upstream func(*dns.Msg) *dns.Msg // nil = defaultUpstream

	wantRcode    int
	wantIPs      string // comma-joined answer IPs
	wantLog      bool   // one log entry expected
	wantBlocked  bool
	wantBy       string
	wantResolved string
	wantUpstream int64
}

func TestHandleDNSQueryPipeline(t *testing.T) {
	adTrie, adBloom := compileTrie(t, "ads", "ads.example.com", "tracker.example")
	secTrie, secBloom := compileTrie(t, "malware", "evil.example", "tracker.example")

	withTries := func(t *testing.T, h *tunHarness, _ *upstream) {
		h.e.SetTries(adTrie, secTrie, adBloom, secBloom)
	}
	withChecker := func(c *fakeChecker) func(*testing.T, *tunHarness, *upstream) {
		return func(t *testing.T, h *tunHarness, up *upstream) {
			withTries(t, h, up)
			h.e.SetDomainChecker(c)
		}
	}
	withResponse := func(rt string, c *fakeChecker) func(*testing.T, *tunHarness, *upstream) {
		return func(t *testing.T, h *tunHarness, up *upstream) {
			h.e.SetBlockResponseType(rt)
			h.e.SetDomainChecker(c)
		}
	}
	blockAll := &fakeChecker{custom: map[string]int{"blocked.example": 1}}

	cases := []pipelineCase{
		{name: "forward A", domain: "clean.example", qtype: dns.TypeA,
			wantIPs: "192.0.2.1", wantLog: true, wantUpstream: 1},
		{name: "forward AAAA", domain: "clean.example", qtype: dns.TypeAAAA,
			wantIPs: "2001:db8::1", wantLog: true, wantUpstream: 1},
		{name: "custom block CUSTOM_IP A", domain: "blocked.example", qtype: dns.TypeA,
			setup: withResponse("CUSTOM_IP", blockAll), wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "custom"},
		{name: "custom block CUSTOM_IP AAAA", domain: "blocked.example", qtype: dns.TypeAAAA,
			setup: withResponse("CUSTOM_IP", blockAll), wantIPs: "::", wantLog: true, wantBlocked: true, wantBy: "custom"},
		{name: "custom block CUSTOM_IP MX has no answer", domain: "blocked.example", qtype: dns.TypeMX,
			setup: withResponse("CUSTOM_IP", blockAll), wantLog: true, wantBlocked: true, wantBy: "custom"},
		{name: "custom block NXDOMAIN", domain: "blocked.example", qtype: dns.TypeA,
			setup: withResponse("NXDOMAIN", blockAll), wantRcode: dns.RcodeNameError, wantLog: true, wantBlocked: true, wantBy: "custom"},
		{name: "custom block REFUSED", domain: "blocked.example", qtype: dns.TypeA,
			setup: withResponse("REFUSED", blockAll), wantRcode: dns.RcodeRefused, wantLog: true, wantBlocked: true, wantBy: "custom"},
		{name: "custom block reason", domain: "blocked.example", qtype: dns.TypeA,
			setup:   withChecker(&fakeChecker{custom: map[string]int{"blocked.example": 1}, reason: map[string]string{"blocked.example": "user_rule"}}),
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "user_rule"},
		{name: "custom allow beats trie", domain: "ads.example.com", qtype: dns.TypeA,
			setup:   withChecker(&fakeChecker{custom: map[string]int{"ads.example.com": 0}}),
			wantIPs: "192.0.2.1", wantLog: true, wantUpstream: 1},
		{name: "ad trie", domain: "ads.example.com", qtype: dns.TypeA, setup: withTries,
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "ads"},
		{name: "ad trie parent match, mixed case", domain: "CDN.Ads.Example.com", qtype: dns.TypeA, setup: withTries,
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "ads"},
		{name: "security trie", domain: "evil.example", qtype: dns.TypeA, setup: withTries,
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "malware"},
		{name: "both tries attribute security first", domain: "tracker.example", qtype: dns.TypeA, setup: withTries,
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "malware,ads"},
		{name: "trie miss forwards", domain: "example.com", qtype: dns.TypeA, setup: withTries,
			wantIPs: "192.0.2.1", wantLog: true, wantUpstream: 1},
		{name: "firewall blocks app", domain: "clean.example", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetAppResolver(&fakeAppResolver{name: "com.blocked"})
				h.e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"com.blocked": true}})
			},
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "firewall"},
		{name: "firewall NXDOMAIN", domain: "clean.example", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetBlockResponseType("NXDOMAIN")
				h.e.SetAppResolver(&fakeAppResolver{name: "com.blocked"})
				h.e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"com.blocked": true}})
			},
			wantRcode: dns.RcodeNameError, wantLog: true, wantBlocked: true, wantBy: "firewall"},
		{name: "firewall REFUSED", domain: "clean.example", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetBlockResponseType("REFUSED")
				h.e.SetAppResolver(&fakeAppResolver{name: "com.blocked"})
				h.e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"com.blocked": true}})
			},
			wantRcode: dns.RcodeRefused, wantLog: true, wantBlocked: true, wantBy: "firewall"},
		{name: "firewall allows other app", domain: "clean.example", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetAppResolver(&fakeAppResolver{name: "com.fine"})
				h.e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"com.blocked": true}})
			},
			wantIPs: "192.0.2.1", wantLog: true, wantUpstream: 1},
		{name: "DoH bootstrap blocked", domain: "cloudflare-dns.com", qtype: dns.TypeA,
			setup:   func(t *testing.T, h *tunHarness, _ *upstream) { h.e.SetDoHBlocklist("cloudflare-dns.com") },
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "doh_bypass_protection"},
		{name: "upstream all-zero A is upstream block", domain: "clean.example", qtype: dns.TypeA,
			upstream: answerWith("A 0.0.0.0", "A 0.0.0.0"),
			wantIPs:  "0.0.0.0,0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "upstream_dns", wantUpstream: 1},
		{name: "upstream :: AAAA is upstream block", domain: "clean.example", qtype: dns.TypeAAAA,
			upstream: answerWith("AAAA ::"),
			wantIPs:  "::", wantLog: true, wantBlocked: true, wantBy: "upstream_dns", wantUpstream: 1},
		{name: "upstream mixed zero and real is not a block", domain: "clean.example", qtype: dns.TypeA,
			upstream: answerWith("A 0.0.0.0", "A 192.0.2.9"),
			wantIPs:  "0.0.0.0,192.0.2.9", wantLog: true, wantUpstream: 1},
		{name: "upstream CNAME only is not a block", domain: "clean.example", qtype: dns.TypeA,
			upstream: answerWith("CNAME other.example."),
			wantLog:  true, wantUpstream: 1},
		{name: "upstream NXDOMAIN passes through", domain: "typo.example", qtype: dns.TypeA,
			upstream:  func(r *dns.Msg) *dns.Msg { m := new(dns.Msg); m.SetRcode(r, dns.RcodeNameError); return m },
			wantRcode: dns.RcodeNameError, wantLog: true, wantUpstream: 1},
		{name: "resolver failure is SERVFAIL", domain: "clean.example", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.resolver.Configure(ProtocolPlain, deadUpstream, deadUpstream, "")
			},
			wantRcode: dns.RcodeServerFailure, wantLog: true},
		{name: "local asset host", domain: LocalAssetHost, qtype: dns.TypeA,
			wantIPs: "198.51.100.1"},
		{name: "safesearch cached redirect", domain: "www.google.com", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetSafeSearch(true)
				h.e.safeSearch.CacheIP("forcesafesearch.google.com", net.IPv4(216, 239, 38, 120))
			},
			wantIPs: "216.239.38.120", wantLog: true, wantResolved: "216.239.38.120"},
		{name: "safesearch lazy resolve via primary", domain: "www.bing.com", qtype: dns.TypeA,
			setup:   func(t *testing.T, h *tunHarness, _ *upstream) { h.e.SetSafeSearch(true) },
			wantIPs: "192.0.2.1", wantLog: true, wantResolved: "192.0.2.1", wantUpstream: 1},
		{name: "safesearch AAAA redirect is empty", domain: "www.google.com", qtype: dns.TypeAAAA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetSafeSearch(true)
				h.e.safeSearch.CacheIP("forcesafesearch.google.com", net.IPv4(216, 239, 38, 120))
			},
			wantLog: true, wantResolved: "216.239.38.120"},
		{name: "safesearch resolve failure falls through to forward", domain: "www.google.com", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetSafeSearch(true)
				h.e.primaryDNS = deadUpstream
			},
			wantIPs: "192.0.2.1", wantLog: true, wantUpstream: 1},
		{name: "youtube restricted redirect", domain: "m.youtube.com", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetYouTubeRestricted(true)
				h.e.safeSearch.CacheIP("restrict.youtube.com", net.IPv4(216, 239, 38, 120))
			},
			wantIPs: "216.239.38.120", wantLog: true, wantResolved: "216.239.38.120"},
		{name: "youtube resolve failure falls through", domain: "m.youtube.com", qtype: dns.TypeA,
			setup: func(t *testing.T, h *tunHarness, _ *upstream) {
				h.e.SetYouTubeRestricted(true)
				h.e.primaryDNS = deadUpstream
			},
			wantIPs: "192.0.2.1", wantLog: true, wantUpstream: 1},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			reply := tc.upstream
			if reply == nil {
				reply = defaultUpstream
			}
			up := startUpstream(t, reply)
			h := newTunHarness(t, up.addr)
			if tc.setup != nil {
				tc.setup(t, h, up)
			}

			info := queryInfo(t, tc.domain, tc.qtype)
			h.e.handleDNSQuery(info)
			m := h.mustRead(t)

			if m.Id != 0x4242 || !m.Response {
				t.Errorf("reply id=%#x response=%v, want 0x4242/true", m.Id, m.Response)
			}
			if m.Rcode != tc.wantRcode {
				t.Errorf("rcode = %s, want %s", dns.RcodeToString[m.Rcode], dns.RcodeToString[tc.wantRcode])
			}
			if got := strings.Join(answerIPs(m), ","); got != tc.wantIPs {
				t.Errorf("answer IPs = %q, want %q", got, tc.wantIPs)
			}
			if got := up.count.Load(); got != tc.wantUpstream {
				t.Errorf("upstream queries = %d, want %d", got, tc.wantUpstream)
			}

			logs := h.log.all()
			if !tc.wantLog {
				if len(logs) != 0 {
					t.Errorf("log entries = %+v, want none", logs)
				}
				return
			}
			if len(logs) != 1 {
				t.Fatalf("log entries = %+v, want 1", logs)
			}
			l := logs[0]
			if l.domain != tc.domain || l.blocked != tc.wantBlocked || l.blockedBy != tc.wantBy || l.qtype != int(tc.qtype) || l.resolvedIP != tc.wantResolved {
				t.Errorf("log = %+v, want domain=%s blocked=%v by=%q resolved=%q", l, tc.domain, tc.wantBlocked, tc.wantBy, tc.wantResolved)
			}
		})
	}
}

func TestHandleDNSQueryStats(t *testing.T) {
	up := startUpstream(t, defaultUpstream)
	h := newTunHarness(t, up.addr)
	h.e.SetDomainChecker(&fakeChecker{custom: map[string]int{"blocked.example": 1}})

	for _, d := range []string{"a.example", "blocked.example", "b.example"} {
		h.e.handleDNSQuery(queryInfo(t, d, dns.TypeA))
		h.mustRead(t)
	}
	var s Stats
	if err := json.Unmarshal([]byte(h.e.GetStats()), &s); err != nil {
		t.Fatal(err)
	}
	if s.TotalQueries != 3 || s.BlockedQueries != 1 {
		t.Errorf("stats = %+v, want total 3 blocked 1", s)
	}
}

func TestHandleDNSQueryDropsWhenStoppedOrNoResolver(t *testing.T) {
	up := startUpstream(t, defaultUpstream)
	h := newTunHarness(t, up.addr)

	h.e.mu.Lock()
	h.e.running = false
	h.e.mu.Unlock()
	h.e.handleDNSQuery(queryInfo(t, "clean.example", dns.TypeA))
	if m := h.read(t, 100*time.Millisecond); m != nil {
		t.Errorf("stopped engine answered: %v", m)
	}

	h.e.mu.Lock()
	h.e.running = true
	r := h.e.resolver
	h.e.resolver = nil
	h.e.mu.Unlock()
	defer r.Shutdown()
	h.e.handleDNSQuery(queryInfo(t, "clean.example", dns.TypeA))
	if m := h.read(t, 100*time.Millisecond); m != nil {
		t.Errorf("engine without resolver answered: %v", m)
	}
	// Without a resolver SafeSearch can't resolve and falls through too.
	h.e.SetSafeSearch(true)
	h.e.handleDNSQuery(queryInfo(t, "www.google.com", dns.TypeA))
	if m := h.read(t, 100*time.Millisecond); m != nil {
		t.Errorf("engine without resolver answered SafeSearch: %v", m)
	}
	if up.count.Load() != 0 {
		t.Errorf("upstream saw %d queries, want 0", up.count.Load())
	}
}

func TestHandleDNSQuerySplitDNS(t *testing.T) {
	up := startUpstream(t, defaultUpstream)
	h := newTunHarness(t, up.addr)
	adapter := &fakeAdapter{}
	h.e.router.SetAdapter(adapter)
	h.e.SetSplitDNSZones("corp, Lan")
	h.e.applySplitDNS("10.8.0.1")
	// Split zones take precedence over blocking.
	h.e.SetDomainChecker(&fakeChecker{custom: map[string]int{"git.corp": 1}})

	h.e.handleDNSQuery(queryInfo(t, "git.corp", dns.TypeA))
	if m := h.read(t, 100*time.Millisecond); m != nil {
		t.Errorf("split query answered locally: %v", m)
	}
	adapter.mu.Lock()
	pkts := adapter.packets
	adapter.mu.Unlock()
	if len(pkts) != 1 {
		t.Fatalf("adapter got %d packets, want 1", len(pkts))
	}
	info := ParseTUNPacket(pkts[0], len(pkts[0]))
	if info == nil || !info.DestIP.Equal(net.IPv4(10, 8, 0, 1)) || info.DestPort != 53 || info.SourcePort != 40000 || info.Domain != "git.corp" {
		t.Errorf("routed packet = %+v, want 10.0.0.2:40000 -> 10.8.0.1:53 for git.corp", info)
	}
	if logs := h.log.all(); len(logs) != 1 || logs[0].blocked {
		t.Errorf("logs = %+v, want one unblocked entry", logs)
	}
	if up.count.Load() != 0 {
		t.Errorf("upstream saw %d queries, want 0", up.count.Load())
	}

	// Clearing the zones disables split DNS.
	h.e.SetSplitDNSZones("")
	if z := h.e.resolver.SplitZones(); len(z) != 0 {
		t.Errorf("zones after clear = %v", z)
	}
}

func TestParseSplitZones(t *testing.T) {
	if got := parseSplitZones(""); got != nil {
		t.Errorf("parseSplitZones(\"\") = %v", got)
	}
	if got := strings.Join(parseSplitZones(" Corp ,,lan, "), "|"); got != "corp|lan" {
		t.Errorf("parseSplitZones = %q", got)
	}
}

func TestIsUpstreamBlocked(t *testing.T) {
	pack := func(f func(*dns.Msg) *dns.Msg) []byte {
		raw, _ := f(msgFor("x.example", dns.TypeA)).Pack()
		return raw
	}
	tests := []struct {
		name string
		raw  []byte
		want bool
	}{
		{"garbage", []byte{1, 2, 3}, false},
		{"empty answer", pack(answerWith()), false},
		{"all zero A", pack(answerWith("A 0.0.0.0")), true},
		{"all zero mixed families", pack(answerWith("A 0.0.0.0", "AAAA ::")), true},
		{"one real", pack(answerWith("A 0.0.0.0", "A 1.2.3.4")), false},
		{"CNAME then zero A", pack(answerWith("CNAME y.example.", "A 0.0.0.0")), true},
		{"TXT only", pack(answerWith(`TXT "0.0.0.0"`)), false},
	}
	for _, tt := range tests {
		if got := isUpstreamBlocked(tt.raw); got != tt.want {
			t.Errorf("%s: isUpstreamBlocked = %v, want %v", tt.name, got, tt.want)
		}
	}
}
