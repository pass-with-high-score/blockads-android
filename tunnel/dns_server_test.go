package tunnel

import (
	"net"
	"strings"
	"testing"

	"github.com/miekg/dns"
)

// newServeEngine returns an engine wired for serveDNS against upAddr. The
// standalone path does not check e.running or write to a TUN.
func newServeEngine(t *testing.T, upAddr string) (*Engine, *entryLog) {
	t.Helper()
	e := NewEngine()
	log := &entryLog{}
	e.SetLogCallback(log)
	e.SetDNS("PLAIN", upAddr, upAddr, "")
	r := NewResolver(nil)
	r.Configure(ProtocolPlain, upAddr, upAddr, "")
	e.mu.Lock()
	e.resolver = r
	e.mu.Unlock()
	t.Cleanup(e.Stop)
	return e, log
}

func udpClient(port int) *net.UDPAddr { return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port} }

func TestServeDNSPipeline(t *testing.T) {
	adTrie, adBloom := compileTrie(t, "ads", "ads.example.com")
	secTrie, secBloom := compileTrie(t, "malware", "evil.example")

	type setupFn func(e *Engine)
	cases := []struct {
		name     string
		domain   string
		qtype    uint16
		setup    setupFn
		upstream func(*dns.Msg) *dns.Msg
		override string

		wantRcode    int
		wantIPs      string
		wantLog      bool
		wantBlocked  bool
		wantBy       string
		wantApp      string
		wantResolved string
	}{
		{name: "forward", domain: "clean.example", qtype: dns.TypeA, wantIPs: "192.0.2.1", wantLog: true, wantApp: "RootProxy"},
		{name: "forward with app override", domain: "clean.example", qtype: dns.TypeA, override: "com.real.app",
			wantIPs: "192.0.2.1", wantLog: true, wantApp: "com.real.app"},
		{name: "local asset A", domain: LocalAssetHost, qtype: dns.TypeA, wantIPs: "198.51.100.1"},
		{name: "local asset AAAA empty", domain: LocalAssetHost, qtype: dns.TypeAAAA},
		{name: "DoH bootstrap", domain: "dns.google", qtype: dns.TypeA,
			setup:   func(e *Engine) { e.SetDoHBlocklist("dns.google") },
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "doh_bypass_protection", wantApp: "RootProxy"},
		{name: "firewall via app resolver", domain: "clean.example", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetAppResolver(&fakeAppResolver{name: "com.blocked"})
				e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"com.blocked": true}})
			},
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "firewall", wantApp: "com.blocked"},
		{name: "firewall skips RootProxy", domain: "clean.example", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"RootProxy": true}})
			},
			wantIPs: "192.0.2.1", wantLog: true, wantApp: "RootProxy"},
		{name: "custom block AAAA", domain: "blocked.example", qtype: dns.TypeAAAA,
			setup:   func(e *Engine) { e.SetDomainChecker(&fakeChecker{custom: map[string]int{"blocked.example": 1}}) },
			wantIPs: "::", wantLog: true, wantBlocked: true, wantBy: "custom", wantApp: "RootProxy"},
		{name: "custom block MX", domain: "blocked.example", qtype: dns.TypeMX,
			setup:   func(e *Engine) { e.SetDomainChecker(&fakeChecker{custom: map[string]int{"blocked.example": 1}}) },
			wantLog: true, wantBlocked: true, wantBy: "custom", wantApp: "RootProxy"},
		{name: "custom block NXDOMAIN with reason", domain: "blocked.example", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetBlockResponseType("NXDOMAIN")
				e.SetDomainChecker(&fakeChecker{custom: map[string]int{"blocked.example": 1}, reason: map[string]string{"blocked.example": "user_rule"}})
			},
			wantRcode: dns.RcodeNameError, wantLog: true, wantBlocked: true, wantBy: "user_rule", wantApp: "RootProxy"},
		{name: "custom block REFUSED", domain: "blocked.example", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetBlockResponseType("REFUSED")
				e.SetDomainChecker(&fakeChecker{custom: map[string]int{"blocked.example": 1}})
			},
			wantRcode: dns.RcodeRefused, wantLog: true, wantBlocked: true, wantBy: "custom", wantApp: "RootProxy"},
		{name: "custom allow beats trie", domain: "ads.example.com", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetTries(adTrie, secTrie, adBloom, secBloom)
				e.SetDomainChecker(&fakeChecker{custom: map[string]int{"ads.example.com": 0}})
			},
			wantIPs: "192.0.2.1", wantLog: true, wantApp: "RootProxy"},
		{name: "ad trie", domain: "x.ads.example.com", qtype: dns.TypeA,
			setup:   func(e *Engine) { e.SetTries(adTrie, secTrie, adBloom, secBloom) },
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "ads", wantApp: "RootProxy"},
		{name: "security trie", domain: "evil.example", qtype: dns.TypeA,
			setup:   func(e *Engine) { e.SetTries(adTrie, secTrie, adBloom, secBloom) },
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "malware", wantApp: "RootProxy"},
		{name: "IsBlocked fallback default reason", domain: "kotlin.example", qtype: dns.TypeA,
			setup:   func(e *Engine) { e.SetDomainChecker(&fakeChecker{blocked: map[string]bool{"kotlin.example": true}}) },
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "filter_list", wantApp: "RootProxy"},
		{name: "IsBlocked fallback reason", domain: "kotlin.example", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetDomainChecker(&fakeChecker{blocked: map[string]bool{"kotlin.example": true}, reason: map[string]string{"kotlin.example": "easylist"}})
			},
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "easylist", wantApp: "RootProxy"},
		{name: "safesearch cached A", domain: "www.google.com", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetSafeSearch(true)
				e.safeSearch.CacheIP("forcesafesearch.google.com", net.IPv4(216, 239, 38, 120))
			},
			wantIPs: "216.239.38.120", wantLog: true, wantApp: "RootProxy", wantResolved: "216.239.38.120"},
		{name: "safesearch lazy resolve", domain: "www.bing.com", qtype: dns.TypeA,
			setup:   func(e *Engine) { e.SetSafeSearch(true) },
			wantIPs: "192.0.2.1", wantLog: true, wantApp: "RootProxy", wantResolved: "192.0.2.1"},
		{name: "safesearch resolve failure forwards", domain: "www.google.com", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetSafeSearch(true)
				e.primaryDNS = deadUpstream
			},
			wantIPs: "192.0.2.1", wantLog: true, wantApp: "RootProxy"},
		{name: "youtube cached", domain: "www.youtube.com", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetYouTubeRestricted(true)
				e.safeSearch.CacheIP("restrict.youtube.com", net.IPv4(216, 239, 38, 119))
			},
			wantIPs: "216.239.38.119", wantLog: true, wantApp: "RootProxy", wantResolved: "216.239.38.119"},
		{name: "youtube resolve failure forwards", domain: "www.youtube.com", qtype: dns.TypeA,
			setup: func(e *Engine) {
				e.SetYouTubeRestricted(true)
				e.primaryDNS = deadUpstream
			},
			wantIPs: "192.0.2.1", wantLog: true, wantApp: "RootProxy"},
		{name: "upstream block", domain: "clean.example", qtype: dns.TypeA, upstream: answerWith("A 0.0.0.0"),
			wantIPs: "0.0.0.0", wantLog: true, wantBlocked: true, wantBy: "upstream_dns", wantApp: "RootProxy"},
		{name: "resolver failure SERVFAIL", domain: "clean.example", qtype: dns.TypeA,
			setup:     func(e *Engine) { e.resolver.Configure(ProtocolPlain, deadUpstream, deadUpstream, "") },
			wantRcode: dns.RcodeServerFailure, wantLog: true, wantApp: "RootProxy"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			reply := tc.upstream
			if reply == nil {
				reply = defaultUpstream
			}
			up := startUpstream(t, reply)
			e, log := newServeEngine(t, up.addr)
			if tc.setup != nil {
				tc.setup(e)
			}
			w := &fakeWriter{remote: udpClient(5555)}
			e.serveDNS(w, msgFor(tc.domain, tc.qtype), tc.override)

			if w.writes != 1 {
				t.Fatalf("writes = %d, want 1", w.writes)
			}
			if w.msg.Id != 0x1234 {
				t.Errorf("reply id = %#x, want 0x1234", w.msg.Id)
			}
			if w.msg.Rcode != tc.wantRcode {
				t.Errorf("rcode = %s, want %s", dns.RcodeToString[w.msg.Rcode], dns.RcodeToString[tc.wantRcode])
			}
			if got := strings.Join(answerIPs(w.msg), ","); got != tc.wantIPs {
				t.Errorf("answer IPs = %q, want %q", got, tc.wantIPs)
			}
			logs := log.all()
			if !tc.wantLog {
				if len(logs) != 0 {
					t.Errorf("logs = %+v, want none", logs)
				}
				return
			}
			if len(logs) != 1 {
				t.Fatalf("logs = %+v, want 1", logs)
			}
			l := logs[0]
			if l.domain != tc.domain || l.blocked != tc.wantBlocked || l.blockedBy != tc.wantBy || l.app != tc.wantApp || l.resolvedIP != tc.wantResolved {
				t.Errorf("log = %+v, want blocked=%v by=%q app=%q resolved=%q", l, tc.wantBlocked, tc.wantBy, tc.wantApp, tc.wantResolved)
			}
		})
	}
}

func TestServeDNSEdgeCases(t *testing.T) {
	up := startUpstream(t, defaultUpstream)
	e, log := newServeEngine(t, up.addr)

	// No question: nothing written, nothing logged.
	w := &fakeWriter{remote: udpClient(1)}
	e.ServeDNS(w, new(dns.Msg))
	if w.writes != 0 || len(log.all()) != 0 {
		t.Errorf("empty question: writes=%d logs=%d", w.writes, len(log.all()))
	}

	// Garbage from upstream → SERVFAIL, not logged.
	raw := startRawUpstream(t, []byte{0xde, 0xad, 0xbe, 0xef})
	e.resolver.Configure(ProtocolPlain, raw, raw, "")
	w = &fakeWriter{remote: udpClient(1)}
	e.ServeDNS(w, msgFor("clean.example", dns.TypeA))
	if w.msg == nil || w.msg.Rcode != dns.RcodeServerFailure {
		t.Errorf("garbage upstream reply = %v, want SERVFAIL", w.msg)
	}

	// No resolver (engine stopping) → SERVFAIL.
	e.mu.Lock()
	r := e.resolver
	e.resolver = nil
	e.mu.Unlock()
	defer r.Shutdown()
	w = &fakeWriter{remote: udpClient(1)}
	e.ServeDNS(w, msgFor("clean.example", dns.TypeA))
	if w.msg == nil || w.msg.Rcode != dns.RcodeServerFailure {
		t.Errorf("no resolver reply = %v, want SERVFAIL", w.msg)
	}
	// ... and SafeSearch can't lazily resolve either, so it forwards (and fails).
	e.SetSafeSearch(true)
	w = &fakeWriter{remote: udpClient(1)}
	e.ServeDNS(w, msgFor("www.google.com", dns.TypeA))
	if w.msg == nil || w.msg.Rcode != dns.RcodeServerFailure {
		t.Errorf("no resolver SafeSearch reply = %v, want SERVFAIL", w.msg)
	}
}

// startRawUpstream answers every datagram with payload.
func startRawUpstream(t *testing.T, payload []byte) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 512)
		for {
			_, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = pc.WriteTo(payload, from)
		}
	}()
	return pc.LocalAddr().String()
}

// The app resolver gets the client's source port and IP from whatever
// address type the server hands over.
func TestServeDNSAppResolution(t *testing.T) {
	up := startUpstream(t, defaultUpstream)
	tests := []struct {
		name     string
		remote   net.Addr
		wantPort int
		wantIP   string // "" = resolver must not be called
	}{
		{"udp4", &net.UDPAddr{IP: net.IPv4(10, 1, 2, 3), Port: 4001}, 4001, "10.1.2.3"},
		{"udp nil ip", &net.UDPAddr{Port: 4002}, 4002, "127.0.0.1"},
		{"tcp4", &net.TCPAddr{IP: net.IPv4(10, 1, 2, 4), Port: 4003}, 4003, "10.1.2.4"},
		{"tcp nil ip", &net.TCPAddr{Port: 4007}, 4007, "127.0.0.1"},
		{"udp6", &net.UDPAddr{IP: net.ParseIP("fd00::5"), Port: 4004}, 4004, "fd00::5"},
		{"string addr", otherAddr("10.1.2.6:4005"), 4005, "10.1.2.6"},
		{"string addr bad host", otherAddr("nohost:4006"), 4006, "127.0.0.1"},
		{"string addr bad port", otherAddr("10.1.2.7:x"), 0, ""},
		{"port zero", &net.UDPAddr{IP: net.IPv4(10, 1, 2, 8)}, 0, ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			e, log := newServeEngine(t, up.addr)
			ar := &fakeAppResolver{name: "com.resolved"}
			e.SetAppResolver(ar)
			e.ServeDNS(&fakeWriter{remote: tt.remote}, msgFor("clean.example", dns.TypeA))

			ar.mu.Lock()
			port, ip := ar.lastPort, net.IP(ar.lastIP)
			ar.mu.Unlock()
			wantApp := "com.resolved"
			if tt.wantIP == "" {
				wantApp = "RootProxy"
				if ip != nil {
					t.Errorf("resolver called with %v:%d, want no call", ip, port)
				}
			} else if port != tt.wantPort || ip.String() != tt.wantIP {
				t.Errorf("resolver got %v:%d, want %s:%d", ip, port, tt.wantIP, tt.wantPort)
			}
			if l := log.all(); len(l) != 1 || l[0].app != wantApp {
				t.Errorf("logs = %+v, want app %s", l, wantApp)
			}
		})
	}

	// A resolver that returns "" keeps RootProxy; a nil remote skips lookup.
	e, log := newServeEngine(t, up.addr)
	e.SetAppResolver(&fakeAppResolver{})
	e.ServeDNS(&fakeWriter{remote: udpClient(9)}, msgFor("clean.example", dns.TypeA))
	e.ServeDNS(&fakeWriter{}, msgFor("clean.example", dns.TypeA))
	for _, l := range log.all() {
		if l.app != "RootProxy" {
			t.Errorf("app = %q, want RootProxy", l.app)
		}
	}
}

func TestLookupIP(t *testing.T) {
	up := startUpstream(t, defaultUpstream)
	e, _ := newServeEngine(t, up.addr)
	ip, err := e.lookupIP("clean.example")
	if err != nil || ip.String() != "192.0.2.1" {
		t.Fatalf("lookupIP = %v, %v", ip, err)
	}

	up.reply.Store(answerWith("AAAA 2001:db8::1"))
	if _, err := e.lookupIP("v6only.example"); err == nil || !strings.Contains(err.Error(), "no A record") {
		t.Errorf("AAAA-only err = %v", err)
	}

	raw := startRawUpstream(t, []byte{0xde, 0xad})
	e.resolver.Configure(ProtocolPlain, raw, raw, "")
	if _, err := e.lookupIP("clean.example"); err == nil || !strings.Contains(err.Error(), "unpack") {
		t.Errorf("garbage err = %v", err)
	}

	if _, err := e.lookupIP(strings.Repeat("a", 70) + ".example"); err == nil || !strings.Contains(err.Error(), "pack") {
		t.Errorf("over-long label err = %v", err)
	}

	e.mu.Lock()
	r := e.resolver
	e.resolver = nil
	e.mu.Unlock()
	defer r.Shutdown()
	if _, err := e.lookupIP("clean.example"); err == nil {
		t.Error("lookupIP without resolver succeeded")
	}
}

// When the configured upstream fails, lookupIP retries against
// hard-coded public resolvers over plaintext UDP, regardless of the
// protocol the user picked.
func TestLookupIPNoPublicPlaintextFallback(t *testing.T) {
	t.Skip("known bug: lookupIP falls back to 1.1.1.1/8.8.8.8 over plaintext UDP")
	e, _ := newServeEngine(t, deadUpstream)
	if _, err := e.lookupIP("clean.example"); err == nil {
		t.Error("lookupIP answered via a public plaintext resolver")
	}
}

func TestStandaloneBlockReplyPacks(t *testing.T) {
	// Names needing escapes still produce a well-formed reply (the fake
	// writer packs the message, as the real server does).
	for _, name := range []string{`a\ b.example.`, `semi\;colon.example.`, `paren\(.example.`, `x\000y.example.`} {
		e, _ := newServeEngine(t, deadUpstream)
		e.SetDomainChecker(&fakeChecker{custom: map[string]int{strings.ToLower(strings.TrimSuffix(name, ".")): 1}})
		m := new(dns.Msg)
		m.SetQuestion(name, dns.TypeA)
		w := &fakeWriter{remote: udpClient(1)}
		e.ServeDNS(w, m)
		if w.writes != 1 || len(w.msg.Answer) != 1 {
			t.Errorf("%s: writes=%d reply=%v", name, w.writes, w.msg)
		}
	}
}
