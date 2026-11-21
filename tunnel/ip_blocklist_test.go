package tunnel

import (
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/miekg/dns"
	"github.com/nqmgaming/blockads-tunnel/internal/trie"
)

func TestIPBlocklist_LoadAndMatch(t *testing.T) {
	dir := t.TempDir()
	ipsFile := filepath.Join(dir, "emerging.ips")
	content := "# comment\n1.10.16.0/20\n162.243.103.246\n2001:db8::/32\n"
	if err := os.WriteFile(ipsFile, []byte(content), 0644); err != nil {
		t.Fatal(err)
	}

	e := NewEngine()
	if err := e.SetIPBlocklistFromFiles(ipsFile); err != nil {
		t.Fatalf("SetIPBlocklistFromFiles failed: %v", err)
	}

	if !e.isIPBlocked(net.ParseIP("162.243.103.246")) {
		t.Fatalf("expected single IPv4 to be blocked")
	}
	if !e.isIPBlocked(net.ParseIP("1.10.20.5")) {
		t.Fatalf("expected IPv4 in /20 CIDR to be blocked")
	}
	if e.isIPBlocked(net.ParseIP("1.10.32.1")) {
		t.Fatalf("expected IPv4 outside /20 CIDR to be allowed")
	}
	if !e.isIPBlocked(net.ParseIP("2001:db8::42")) {
		t.Fatalf("expected IPv6 in /32 CIDR to be blocked")
	}
	if e.isIPBlocked(net.ParseIP("8.8.8.8")) {
		t.Fatalf("expected 8.8.8.8 to be allowed")
	}

	// DNS response inspection
	msg := new(dns.Msg)
	msg.SetQuestion("evil.example.", dns.TypeA)
	msg.Answer = append(msg.Answer, &dns.A{
		Hdr: dns.RR_Header{Name: "evil.example.", Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 60},
		A:   net.ParseIP("1.10.17.9").To4(),
	})
	rawResp, err := msg.Pack()
	if err != nil {
		t.Fatal(err)
	}
	if blocked, ip := checkDNSResponseIPBlocklist(rawResp, e.ipBlocklist.Load()); !blocked || ip != "1.10.17.9" {
		t.Fatalf("expected DNS response IP to be blocked, got blocked=%v ip=%q", blocked, ip)
	}

	// Clear
	if err := e.SetIPBlocklistFromFiles(""); err != nil {
		t.Fatalf("clear failed: %v", err)
	}
	if e.isIPBlocked(net.ParseIP("162.243.103.246")) {
		t.Fatalf("expected blocklist to be empty after clear")
	}
}

func TestCompileFilterList_IPsSidecar(t *testing.T) {
	dir := t.TempDir()
	inPath := filepath.Join(dir, "emerging.txt")
	triePath := filepath.Join(dir, "emerging.trie")
	bloomPath := filepath.Join(dir, "emerging.bloom")

	raw := strings.Join([]string{
		"# Emerging Threats Block IPs",
		"1.10.16.0/20",
		"162.243.103.246 # botnet",
		"192.168.1.1",        // private - skipped
		"10.0.0.0/8",         // private - skipped
		"127.0.0.1",          // loopback - skipped
		"1.0.0.0/4",          // too broad < /8 - skipped
		"2001:db8::/32",      // valid IPv6 CIDR
		"0.0.0.0 ads.test",   // hosts entry - not an IP rule
	}, "\n")
	if err := os.WriteFile(inPath, []byte(raw), 0644); err != nil {
		t.Fatal(err)
	}

	count, err := trie.CompileFilterList(inPath, triePath, bloomPath)
	if err != nil {
		t.Fatalf("CompileFilterList failed: %v", err)
	}
	// 1 domain (ads.test) + 3 valid IPs/CIDRs
	if count != 4 {
		t.Fatalf("expected count=4, got %d", count)
	}

	ipsBytes, err := os.ReadFile(filepath.Join(dir, "emerging.ips"))
	if err != nil {
		t.Fatalf("expected .ips sidecar file: %v", err)
	}
	ipsText := string(ipsBytes)
	for _, want := range []string{"1.10.16.0/20", "162.243.103.246", "2001:db8::/32"} {
		if !strings.Contains(ipsText, want) {
			t.Errorf("missing %q in .ips output:\n%s", want, ipsText)
		}
	}
	for _, reject := range []string{"192.168.1.1", "10.0.0.0/8", "127.0.0.1", "1.0.0.0/4"} {
		if strings.Contains(ipsText, reject) {
			t.Errorf("unexpected %q in .ips output:\n%s", reject, ipsText)
		}
	}
}
