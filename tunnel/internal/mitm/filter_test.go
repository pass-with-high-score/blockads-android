package mitm

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestFilterUIDs(t *testing.T) {
	f := NewMitmFilter()
	if f.HasAllowedUIDs() || f.IsUIDAllowed(10123) {
		t.Fatal("new filter should have no allowed UIDs")
	}
	f.SetAllowedUIDs([]int{10123, 10456})
	if !f.HasAllowedUIDs() || !f.IsUIDAllowed(10123) || !f.IsUIDAllowed(10456) || f.IsUIDAllowed(10789) {
		t.Fatal("allowed UID set mismatch")
	}
	f.SetAllowedUIDs(nil)
	if f.HasAllowedUIDs() || f.IsUIDAllowed(10123) {
		t.Fatal("SetAllowedUIDs(nil) should clear the set")
	}
}

func TestFilterAdPathPatterns(t *testing.T) {
	f := NewMitmFilter()
	if f.IsAdPathBlocked("/pagead/x") {
		t.Fatal("no patterns should block nothing")
	}
	f.SetAdPathPatterns([]string{" /PageAd ", "", "  ", "/ads.js"})
	for path, want := range map[string]bool{
		"/pagead/conversion": true,
		"/PAGEAD/x":          true,
		"/static/ads.js":     true,
		"/index.html":        false,
		"/page":              false,
	} {
		if got := f.IsAdPathBlocked(path); got != want {
			t.Errorf("IsAdPathBlocked(%q) = %v, want %v", path, got, want)
		}
	}
	f.SetAdPathPatterns(nil)
	if f.IsAdPathBlocked("/pagead/x") {
		t.Fatal("cleared patterns still block")
	}
}

func TestIsInterceptionAllowed(t *testing.T) {
	f := NewMitmFilter()
	f.SetExtraPassthroughSuffixes([]string{"VietcomBank.com.vn", "# comment", "// comment", "", ".already-dotted.example", "  pinned.example  "})
	f.BlacklistDomain("pinned-by-probe.example")

	cases := map[string]bool{
		"news.example.com":             true,
		"NEWS.EXAMPLE.COM:443":         true,
		"  news.example.com  ":         true,
		"www.google.com":               false, // hardcoded suffix
		"google.com":                   false, // bare suffix
		"notgoogle.com":                true,  // suffix needs a dot boundary
		"cdn.fbcdn.net":                false,
		"x.vietcombank.com.vn":         false, // extra suffix, lowercased
		"vietcombank.com.vn":           false,
		"a.already-dotted.example":     false,
		"pinned.example":               false,
		"pinned-by-probe.example":      false, // blacklist
		"PINNED-BY-PROBE.EXAMPLE:8443": false,
		"mybank.example":               false, // keyword
		"login.example.com":            false,
		"shop.example.gov":             false,
		"93.184.216.34":                false, // IPv4
		"93.184.216.34:443":            false,
		"2001:db8::1":                  false, // IPv6 (port strip leaves a colon)
	}
	for host, want := range cases {
		if got := f.IsInterceptionAllowed(host); got != want {
			t.Errorf("IsInterceptionAllowed(%q) = %v, want %v", host, got, want)
		}
	}
}

// Plan Phase 2.1 "filter keyword over-match": substring keywords exclude
// unrelated domains (authors → auth, govee → gov, papaya → pay) from
// filtering entirely.
func TestKeywordOverMatch(t *testing.T) {
	t.Skip("known bug (plan: filter keyword over-match): SNI keywords match as raw substrings")
	f := NewMitmFilter()
	for _, host := range []string{"authors.example.com", "www.govee.com", "papaya.example"} {
		if !f.IsInterceptionAllowed(host) {
			t.Errorf("%q excluded by a keyword substring match", host)
		}
	}
}

func TestIsIPAddress(t *testing.T) {
	for host, want := range map[string]bool{
		"1.2.3.4":     true,
		"::1":         true,
		"example.com": false,
		"":            false,
		"123":         true,
		"1.2.3.a":     false,
	} {
		if got := isIPAddress(host); got != want {
			t.Errorf("isIPAddress(%q) = %v, want %v", host, got, want)
		}
	}
}

func TestPersistentBlacklistRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "blacklist.txt")
	if err := os.WriteFile(path, []byte("# header\n\nPreloaded.Example\n  spaced.example  \n"), 0644); err != nil {
		t.Fatal(err)
	}

	f := NewMitmFilter()
	f.LoadPersistentBlacklist(path)
	if got := f.GetBlacklistCount(); got != 2 {
		t.Fatalf("loaded %d entries, want 2", got)
	}
	if f.IsInterceptionAllowed("preloaded.example") || f.IsInterceptionAllowed("spaced.example") {
		t.Fatal("persisted entries not honored")
	}

	f.BlacklistDomain("New.Example")
	f.BlacklistDomain("new.example") // duplicate, not re-appended
	f.BlacklistDomain("   ")         // ignored
	f.BlacklistDomain("preloaded.example")

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if n := strings.Count(string(data), "new.example\n"); n != 1 {
		t.Fatalf("new.example appended %d times:\n%s", n, data)
	}
	if strings.Count(string(data), "preloaded.example") != 0 {
		t.Fatalf("already-known entry re-appended:\n%s", data)
	}

	g := NewMitmFilter()
	g.LoadPersistentBlacklist(path)
	if g.GetBlacklistCount() != 3 || g.IsInterceptionAllowed("new.example") {
		t.Fatalf("reloaded filter has %d entries", g.GetBlacklistCount())
	}
}

func TestPersistentBlacklistMissingAndUnwritable(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "later.txt")
	f := NewMitmFilter()
	f.LoadPersistentBlacklist(path) // missing file is fine
	if f.GetBlacklistCount() != 0 {
		t.Fatal("missing file loaded entries")
	}
	f.BlacklistDomain("created.example")
	if data, err := os.ReadFile(path); err != nil || string(data) != "created.example\n" {
		t.Fatalf("file not created: %q %v", data, err)
	}

	// Pointing the path at a directory makes appends fail; in-memory still wins.
	g := NewMitmFilter()
	g.LoadPersistentBlacklist(dir)
	g.BlacklistDomain("memory-only.example")
	if g.IsInterceptionAllowed("memory-only.example") {
		t.Fatal("in-memory blacklist ignored after a write failure")
	}
}

func TestFilterConcurrentAccess(t *testing.T) {
	f := NewMitmFilter()
	f.LoadPersistentBlacklist(filepath.Join(t.TempDir(), "bl.txt"))
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				f.SetAllowedUIDs([]int{i, j})
				f.IsUIDAllowed(j)
				f.SetAdPathPatterns([]string{"/ads"})
				f.IsAdPathBlocked("/ads/x")
				f.SetExtraPassthroughSuffixes([]string{"x.example"})
				f.BlacklistDomain("h" + intToStr(j) + ".example")
				f.IsInterceptionAllowed("h" + intToStr(j) + ".example")
				f.GetBlacklistCount()
			}
		}(i)
	}
	wg.Wait()
	if f.GetBlacklistCount() != 50 {
		t.Fatalf("blacklist count = %d, want 50", f.GetBlacklistCount())
	}
}
