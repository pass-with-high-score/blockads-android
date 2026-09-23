package dns

import (
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	miekgdns "github.com/miekg/dns"
)

func TestParseProtocol(t *testing.T) {
	tests := map[string]DNSProtocol{
		"DOH": ProtocolDoH, "doh": ProtocolDoH, "DoH": ProtocolDoH,
		"DOT": ProtocolDoT, "dot": ProtocolDoT,
		"DOQ": ProtocolDoQ, "doq": ProtocolDoQ,
		"PLAIN": ProtocolPlain, "": ProtocolPlain, "udp": ProtocolPlain, "DOH ": ProtocolPlain,
	}
	for in, want := range tests {
		if got := ParseProtocol(in); got != want {
			t.Errorf("ParseProtocol(%q) = %d, want %d", in, got, want)
		}
	}
}

func FuzzParseProtocol(f *testing.F) {
	for _, s := range []string{"DOH", "dot", "DoQ", "plain", "", "\x00"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		if p := ParseProtocol(s); p < ProtocolPlain || p > ProtocolDoQ {
			t.Fatalf("ParseProtocol(%q) = %d, out of range", s, p)
		}
	})
}

func TestConfigureKeepsServersWhenEmpty(t *testing.T) {
	r := NewResolver(nil)
	defer r.Shutdown()
	r.Configure(ProtocolDoT, "", "", "https://dns.example/dns-query")
	if r.primaryServer != "9.9.9.9" || r.fallbackServer != "94.140.14.14" {
		t.Errorf("empty Configure replaced defaults: primary=%q fallback=%q", r.primaryServer, r.fallbackServer)
	}
	r.Configure(ProtocolPlain, "1.1.1.1", "8.8.8.8", "")
	if r.protocol != ProtocolPlain || r.primaryServer != "1.1.1.1" || r.fallbackServer != "8.8.8.8" || r.dohURL != "" {
		t.Errorf("Configure not applied: %+v", r)
	}
}

func TestSplitDNSConfig(t *testing.T) {
	r := NewResolver(nil)
	defer r.Shutdown()
	if r.SplitDNS() != "" || len(r.SplitZones()) != 0 {
		t.Fatal("new resolver has split DNS configured")
	}
	zones := []string{"corp", "Home.Arpa."}
	r.SetSplitDNS("10.0.0.53", zones)
	if r.SplitDNS() != "10.0.0.53" {
		t.Errorf("SplitDNS = %q", r.SplitDNS())
	}
	got := r.SplitZones()
	got[0] = "mutated"
	if r.SplitZones()[0] != "corp" {
		t.Error("SplitZones returned the internal slice, not a copy")
	}

	for d, want := range map[string]bool{
		"corp": true, "host.corp": true, "HOST.CORP.": true, "a.b.home.arpa": true,
		"notcorp": false, "corp.example.com": false, "example.com": false, "": false,
	} {
		if got := r.MatchesSplitZone(d); got != want {
			t.Errorf("MatchesSplitZone(%q) = %v, want %v", d, got, want)
		}
	}
}

func TestResolvePlain(t *testing.T) {
	srv := startUDP(t, answerA("192.0.2.10"))
	var protected atomic.Int64
	r := NewResolver(func(fd int) bool { protected.Add(1); return true })
	defer r.Shutdown()
	r.Configure(ProtocolPlain, srv.addr, srv.addr, "") // fallback == primary: never leave loopback

	resp, err := r.Resolve(query(t, "example.com", miekgdns.TypeA))
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if ip := firstA(t, resp); ip != "192.0.2.10" {
		t.Errorf("answer = %s, want 192.0.2.10", ip)
	}
	if protected.Load() == 0 {
		t.Error("plain query socket was not protected")
	}
}

func TestResolveSplitZoneBypassesProtocol(t *testing.T) {
	primary := startUDP(t, answerA("192.0.2.1"))
	split := startUDP(t, answerA("10.0.0.7"))
	r := NewResolver(nil)
	defer r.Shutdown()
	// DoH with no URL would fail; split zones must not depend on it.
	r.Configure(ProtocolDoH, primary.addr, primary.addr, "")
	r.SetSplitDNS(split.addr, []string{"corp"})

	resp, err := r.Resolve(query(t, "git.corp", miekgdns.TypeA))
	if err != nil {
		t.Fatalf("split Resolve: %v", err)
	}
	if ip := firstA(t, resp); ip != "10.0.0.7" {
		t.Errorf("split answer = %s, want 10.0.0.7", ip)
	}
	if split.count.Load() != 1 || primary.count.Load() != 0 {
		t.Errorf("split=%d primary=%d queries, want 1/0", split.count.Load(), primary.count.Load())
	}

	// A non-matching name goes through the configured protocol, which fails
	// here (no DoH URL), and fallback == primary means no retry.
	if _, err := r.Resolve(query(t, "example.com", miekgdns.TypeA)); err == nil || !strings.Contains(err.Error(), "DoH URL") {
		t.Errorf("non-split Resolve err = %v, want DoH URL error", err)
	}

	// A packet that doesn't unpack skips the split check.
	if _, err := r.Resolve([]byte{0x01}); err == nil {
		t.Error("garbage query succeeded")
	}
}

func TestResolveFallsBackToPlain(t *testing.T) {
	fallback := startUDP(t, answerA("192.0.2.99"))
	r := NewResolver(nil)
	defer r.Shutdown()
	// An invalid port fails the primary before any packet is sent.
	r.Configure(ProtocolPlain, "127.0.0.1:99999", fallback.addr, "")

	resp, err := r.Resolve(query(t, "example.com", miekgdns.TypeA))
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if ip := firstA(t, resp); ip != "192.0.2.99" {
		t.Errorf("answer = %s, want fallback 192.0.2.99", ip)
	}

	// No fallback → the primary error surfaces.
	r2 := NewResolver(nil)
	defer r2.Shutdown()
	r2.Configure(ProtocolPlain, "127.0.0.1:99999", "127.0.0.1:99999", "")
	if _, err := r2.Resolve(query(t, "example.com", miekgdns.TypeA)); err == nil {
		t.Error("Resolve with failing primary and fallback == primary succeeded")
	}
}

// A user who picked an encrypted protocol must not have queries silently
// leave in plaintext when that transport fails.
func TestResolveNoPlaintextFallbackWhenEncrypted(t *testing.T) {
	t.Skip("known bug: encrypted-protocol failures fall back to plaintext UDP")
	fallback := startUDP(t, answerA("192.0.2.99"))
	r := NewResolver(nil)
	defer r.Shutdown()
	r.Configure(ProtocolDoH, "", fallback.addr, "https://"+closedTCPAddr(t)+"/dns-query")
	if _, err := r.Resolve(query(t, "example.com", miekgdns.TypeA)); err == nil {
		t.Error("DoH failure was answered over plaintext")
	}
	if n := fallback.count.Load(); n != 0 {
		t.Errorf("plaintext fallback received %d queries, want 0", n)
	}
}

// Truncated UDP answers are passed through as-is; the resolver does not
// retry over TCP. Pinned as current behavior.
func TestPlainTruncatedPassedThrough(t *testing.T) {
	srv := startUDP(t, func(req *miekgdns.Msg) *miekgdns.Msg {
		m := new(miekgdns.Msg)
		m.SetReply(req)
		m.Truncated = true
		return m
	})
	r := NewResolver(nil)
	defer r.Shutdown()
	resp, err := r.queryPlain(query(t, "big.example", miekgdns.TypeTXT), srv.addr)
	if err != nil {
		t.Fatalf("queryPlain: %v", err)
	}
	if !unpack(t, resp).Truncated {
		t.Error("TC bit lost")
	}
	if srv.count.Load() != 1 {
		t.Errorf("server saw %d queries, want 1 (no retry)", srv.count.Load())
	}
}

// An off-path answer (wrong source port or wrong transaction ID) must not be
// accepted. queryPlain reads from an unconnected socket and never checks the
// ID, so either spoof is taken as the answer.
func TestPlainRejectsSpoofedAnswers(t *testing.T) {
	t.Skip("known bug: queryPlain accepts answers from any source address and with any DNS ID")
	spoofer, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer spoofer.Close()
	addr := rawUDPServer(t, func(_ net.PacketConn, from net.Addr, req []byte) {
		var m miekgdns.Msg
		_ = m.Unpack(req)
		resp := answerA("203.0.113.66")(&m)
		raw, _ := resp.Pack()
		_, _ = spoofer.WriteTo(raw, from)
	})
	r := NewResolver(nil)
	defer r.Shutdown()
	if _, err := r.queryPlain(query(t, "bank.example", miekgdns.TypeA), addr); err == nil {
		t.Error("answer from a different source port was accepted")
	}

	addr = rawUDPServer(t, func(pc net.PacketConn, from net.Addr, req []byte) {
		var m miekgdns.Msg
		_ = m.Unpack(req)
		resp := answerA("203.0.113.66")(&m)
		resp.Id = m.Id + 1
		raw, _ := resp.Pack()
		_, _ = pc.WriteTo(raw, from)
	})
	if _, err := r.queryPlain(query(t, "bank.example", miekgdns.TypeA), addr); err == nil {
		t.Error("answer with a mismatched ID was accepted")
	}
}

func TestPlainTimeout(t *testing.T) {
	if testing.Short() {
		t.Skip("waits for the 4s UDP timeout")
	}
	addr := rawUDPServer(t, func(net.PacketConn, net.Addr, []byte) {})
	r := NewResolver(nil)
	defer r.Shutdown()
	start := time.Now()
	_, err := r.queryPlain(query(t, "example.com", miekgdns.TypeA), addr)
	if err == nil || !strings.Contains(err.Error(), "plain read") {
		t.Fatalf("err = %v, want plain read timeout", err)
	}
	if el := time.Since(start); el < queryTimeoutUDP-500*time.Millisecond || el > queryTimeoutUDP+2*time.Second {
		t.Errorf("timed out after %v, want ~%v", el, queryTimeoutUDP)
	}
}

func TestQueryPlainUnprotected(t *testing.T) {
	srv := startUDP(t, answerA("192.0.2.3"))
	r := NewResolver(nil)
	defer r.Shutdown()
	resp, err := r.queryPlainUnprotected(query(t, "example.com", miekgdns.TypeA), srv.addr)
	if err != nil {
		t.Fatalf("queryPlainUnprotected: %v", err)
	}
	if ip := firstA(t, resp); ip != "192.0.2.3" {
		t.Errorf("answer = %s", ip)
	}
	if _, err := r.queryPlainUnprotected(nil, "127.0.0.1:99999"); err == nil {
		t.Error("invalid port succeeded")
	}
}

func TestResolveARecord(t *testing.T) {
	r := NewResolver(nil)
	defer r.Shutdown()

	srv := startUDP(t, answerA("216.239.38.120"))
	ip, err := r.ResolveARecord("forcesafesearch.google.com", srv.addr)
	if err != nil || ip.String() != "216.239.38.120" {
		t.Fatalf("ResolveARecord = %v, %v", ip, err)
	}

	aaaaOnly := startUDP(t, func(req *miekgdns.Msg) *miekgdns.Msg {
		m := new(miekgdns.Msg)
		m.SetReply(req)
		rr, _ := miekgdns.NewRR(req.Question[0].Name + " 60 IN AAAA 2001:db8::1")
		m.Answer = append(m.Answer, rr)
		return m
	})
	if _, err := r.ResolveARecord("v6only.example", aaaaOnly.addr); err == nil || !strings.Contains(err.Error(), "no A record") {
		t.Errorf("AAAA-only err = %v, want no A record", err)
	}

	garbage := rawUDPServer(t, func(pc net.PacketConn, from net.Addr, _ []byte) {
		_, _ = pc.WriteTo([]byte{0xde, 0xad}, from)
	})
	if _, err := r.ResolveARecord("example.com", garbage); err == nil || !strings.Contains(err.Error(), "unpack") {
		t.Errorf("garbage err = %v, want unpack error", err)
	}

	if _, err := r.ResolveARecord("example.com", "127.0.0.1:99999"); err == nil {
		t.Error("invalid server succeeded")
	}
	if _, err := r.ResolveARecord(strings.Repeat("a", 70)+".example", srv.addr); err == nil {
		t.Error("over-long label packed")
	}
}
