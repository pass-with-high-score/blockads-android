package tunnel

import (
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/miekg/dns"
)

// decision is the outcome of one query, comparable across the TUN path
// (handleDNSQuery) and the standalone path (serveDNS).
type decision struct {
	blocked   bool
	blockedBy string
	ips       string
}

type orderScenario struct {
	name   string
	domain string
	setup  func(e *Engine)
}

func runTUN(t *testing.T, sc orderScenario, upAddr string) decision {
	t.Helper()
	h := newTunHarness(t, upAddr)
	sc.setup(h.e)
	h.e.handleDNSQuery(queryInfo(t, sc.domain, dns.TypeA))
	m := h.mustRead(t)
	logs := h.log.all()
	if len(logs) != 1 {
		t.Fatalf("TUN logs = %+v, want 1", logs)
	}
	return decision{logs[0].blocked, logs[0].blockedBy, strings.Join(answerIPs(m), ",")}
}

func runStandalone(t *testing.T, sc orderScenario, upAddr string) decision {
	t.Helper()
	e, log := newServeEngine(t, upAddr)
	sc.setup(e)
	w := &fakeWriter{remote: udpClient(40000)}
	e.ServeDNS(w, msgFor(sc.domain, dns.TypeA))
	logs := log.all()
	if w.msg == nil || len(logs) != 1 {
		t.Fatalf("standalone reply=%v logs=%+v", w.msg, logs)
	}
	return decision{logs[0].blocked, logs[0].blockedBy, strings.Join(answerIPs(w.msg), ",")}
}

func blockedApp(e *Engine) {
	e.SetAppResolver(&fakeAppResolver{name: "com.blocked"})
	e.SetFirewallChecker(&fakeFirewall{block: map[string]bool{"com.blocked": true}})
}

func cachedSafeSearch(e *Engine) {
	e.SetSafeSearch(true)
	e.safeSearch.CacheIP("forcesafesearch.google.com", net.IPv4(216, 239, 38, 120))
}

func checkParity(t *testing.T, scenarios []orderScenario) {
	up := startUpstream(t, defaultUpstream)
	for _, sc := range scenarios {
		t.Run(sc.name, func(t *testing.T) {
			tun := runTUN(t, sc, up.addr)
			sa := runStandalone(t, sc, up.addr)
			if tun != sa {
				t.Errorf("TUN %+v != standalone %+v", tun, sa)
			}
		})
	}
}

// Both DNS paths must reach the same decision for the same inputs. These
// scenarios already agree.
func TestDNSPathParity(t *testing.T) {
	adTrie, adBloom := compileTrie(t, "ads", "ads.example.com")
	checkParity(t, []orderScenario{
		{"clean forward", "clean.example", func(e *Engine) {}},
		{"custom block beats trie", "ads.example.com", func(e *Engine) {
			e.SetTries(adTrie, "", adBloom, "")
			e.SetDomainChecker(&fakeChecker{custom: map[string]int{"ads.example.com": 1}})
		}},
		{"custom allow beats trie", "ads.example.com", func(e *Engine) {
			e.SetTries(adTrie, "", adBloom, "")
			e.SetDomainChecker(&fakeChecker{custom: map[string]int{"ads.example.com": 0}})
		}},
		{"trie", "ads.example.com", func(e *Engine) { e.SetTries(adTrie, "", adBloom, "") }},
		{"DoH bootstrap", "dns.google", func(e *Engine) { e.SetDoHBlocklist("dns.google") }},
		{"firewall", "clean.example", blockedApp},
		{"safesearch", "www.google.com", cachedSafeSearch},
	})
}

// The two paths apply the checks in different orders, and only the
// standalone path consults DomainChecker.IsBlocked.
//   - TUN: firewall, split DNS, DoH, SafeSearch, custom rules, tries.
//   - standalone: DoH, firewall, custom rules, SafeSearch, tries, IsBlocked.
func TestDNSPathParityKnownDivergence(t *testing.T) {
	t.Skip("known bug: TUN and standalone DNS paths order their checks differently")
	checkParity(t, []orderScenario{
		{"DoH domain from firewalled app", "dns.google", func(e *Engine) {
			e.SetDoHBlocklist("dns.google")
			blockedApp(e)
		}},
		{"custom block vs safesearch", "www.google.com", func(e *Engine) {
			cachedSafeSearch(e)
			e.SetDomainChecker(&fakeChecker{custom: map[string]int{"www.google.com": 1}})
		}},
		{"custom allow vs safesearch", "www.google.com", func(e *Engine) {
			cachedSafeSearch(e)
			e.SetDomainChecker(&fakeChecker{custom: map[string]int{"www.google.com": 0}})
		}},
		{"IsBlocked fallback", "kotlin.example", func(e *Engine) {
			e.SetDomainChecker(&fakeChecker{blocked: map[string]bool{"kotlin.example": true}})
		}},
	})
}

// blockEverything blocks every name through a custom rule.
type blockEverything struct{}

func (blockEverything) IsBlocked(string) bool        { return true }
func (blockEverything) GetBlockReason(string) string { return "" }
func (blockEverything) HasCustomRule(string) int     { return 1 }

// FuzzServeDNS feeds arbitrary wire messages through serveDNS on the block,
// SafeSearch and local-asset paths (the ones that build answers with
// dns.NewRR). The fake writer packs every reply like the real server does.
func FuzzServeDNS(f *testing.F) {
	for _, q := range []*dns.Msg{
		msgFor("ads.example", dns.TypeA),
		msgFor("ads.example", dns.TypeAAAA),
		msgFor("www.google.com", dns.TypeA),
		msgFor(LocalAssetHost, dns.TypeA),
		msgFor(`we\ ird\;name.example`, dns.TypeA),
		new(dns.Msg),
	} {
		raw, err := q.Pack()
		if err != nil {
			f.Fatal(err)
		}
		f.Add(raw)
	}

	block := NewEngine()
	block.SetDomainChecker(blockEverything{})
	nx := NewEngine()
	nx.SetBlockResponseType("NXDOMAIN")
	nx.SetDomainChecker(blockEverything{})
	ss := NewEngine()
	ss.SetSafeSearch(true)
	ss.SetYouTubeRestricted(true)
	ss.safeSearch.CacheIP("forcesafesearch.google.com", net.IPv4(216, 239, 38, 120))
	ss.safeSearch.CacheIP("strict.bing.com", net.IPv4(204, 79, 197, 220))
	ss.safeSearch.CacheIP("restrict.youtube.com", net.IPv4(216, 239, 38, 119))
	// No resolver: anything not answered locally gets SERVFAIL.

	f.Fuzz(func(t *testing.T, raw []byte) {
		var m dns.Msg
		if err := m.Unpack(raw); err != nil {
			return
		}
		for _, e := range []*Engine{block, nx, ss} {
			w := &fakeWriter{remote: udpClient(1)}
			e.ServeDNS(w, m.Copy())
			if len(m.Question) > 0 && w.writes != 1 {
				t.Fatalf("query %v got %d replies, want 1", m.Question[0], w.writes)
			}
		}
	})
}

// freePort returns a loopback port that is currently free for both UDP and TCP.
func freePort(t *testing.T) int {
	t.Helper()
	for i := 0; i < 20; i++ {
		pc, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		port := pc.LocalAddr().(*net.UDPAddr).Port
		l, err := net.Listen("tcp", pc.LocalAddr().String())
		pc.Close()
		if err == nil {
			l.Close()
			return port
		}
	}
	t.Fatal("no free port")
	return 0
}

func TestStartStandalone(t *testing.T) {
	up := startUpstream(t, defaultUpstream)
	e := NewEngine()
	e.SetDNS("PLAIN", up.addr, up.addr, "")
	e.SetDomainChecker(&fakeChecker{custom: map[string]int{"blocked.example": 1}})
	defer e.Stop()

	port := freePort(t)
	if err := e.StartStandalone(port); err != nil {
		t.Fatalf("StartStandalone: %v", err)
	}
	if !e.IsRunning() {
		t.Error("IsRunning = false after StartStandalone")
	}
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))

	for _, netw := range []string{"udp", "tcp"} {
		c := &dns.Client{Net: netw, Timeout: 2 * time.Second}
		resp, _, err := c.Exchange(msgFor("clean.example", dns.TypeA), addr)
		if err != nil {
			t.Fatalf("%s exchange: %v", netw, err)
		}
		if got := strings.Join(answerIPs(resp), ","); got != "192.0.2.1" {
			t.Errorf("%s forward answer = %q", netw, got)
		}
		resp, _, err = c.Exchange(msgFor("blocked.example", dns.TypeA), addr)
		if err != nil {
			t.Fatalf("%s exchange: %v", netw, err)
		}
		if got := strings.Join(answerIPs(resp), ","); got != "0.0.0.0" {
			t.Errorf("%s block answer = %q", netw, got)
		}
	}

	// Restarting while running replaces the servers and resets the stats.
	port2 := freePort(t)
	if err := e.StartStandalone(port2); err != nil {
		t.Fatalf("restart: %v", err)
	}
	if e.totalQueries.Load() != 0 {
		t.Errorf("stats not reset on restart: %s", e.GetStats())
	}
	c := &dns.Client{Net: "udp", Timeout: 2 * time.Second}
	if _, _, err := c.Exchange(msgFor("clean.example", dns.TypeA), net.JoinHostPort("127.0.0.1", strconv.Itoa(port2))); err != nil {
		t.Errorf("exchange after restart: %v", err)
	}
	e.Stop()
	if e.IsRunning() {
		t.Error("IsRunning = true after Stop")
	}
}

func TestStartStandalonePortInUse(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	e := NewEngine()
	e.SetDNS("PLAIN", deadUpstream, deadUpstream, "")
	defer e.Stop()
	if err := e.StartStandalone(pc.LocalAddr().(*net.UDPAddr).Port); err == nil {
		t.Error("StartStandalone on a busy port succeeded")
	}
}
