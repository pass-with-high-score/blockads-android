package trie

import (
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseDomainLine(t *testing.T) {
	cases := []struct {
		line, want string
	}{
		// Adblock-style network rules
		{"||ads.example.com^", "ads.example.com"},
		{"||Ads.Example.COM^", "ads.example.com"},
		{"||ads.example.com", "ads.example.com"},
		{"||ads.example.com^|", "ads.example.com"},
		{"||ads.example.com/path", ""},
		{"||ads.example.com^?x", ""},
		{"||*.example.com^", ""},
		// Modifiers after ^ are dropped, so scoped rules block the domain everywhere.
		{"||ads.example.com^$third-party", "ads.example.com"},
		{"||ads.example.com^$domain=foo.com", "ads.example.com"},
		// Without ^ the modifier stays in the "domain"; the entry can never match.
		{"||ads.example.com$third-party", "ads.example.com$third-party"},
		{"@@||ads.example.com^", ""},
		{"@@ads.example.com", ""},

		// Hosts-file lines
		{"0.0.0.0 ads.example.com", "ads.example.com"},
		{"127.0.0.1 ads.example.com", "ads.example.com"},
		{"0.0.0.0  ads.example.com", "ads.example.com"},
		{"0.0.0.0 ads.example.com # tracker", "ads.example.com"},
		{"0.0.0.0 ads.example.com#tracker", "ads.example.com"},
		{"0.0.0.0 ADS.Example.com", "ads.example.com"},
		{"0.0.0.0\tads.example.com", ""}, // tab separator isn't recognized
		{"::1 ads.example.com", ""},      // IPv6 sinks aren't recognized
		{"0.0.0.0 localhost", ""},
		{"127.0.0.1 localhost", ""},
		{"0.0.0.0 localhost.localdomain", ""},
		{"0.0.0.0 local", ""},
		{"0.0.0.0 broadcasthost", ""},
		{"0.0.0.0 0.0.0.0", ""},
		{"0.0.0.0", ""},

		// Plain domain lists
		{"ads.example.com", "ads.example.com"},
		{"  ads.example.com  ", "ads.example.com"},
		{"ADS.EXAMPLE.COM", "ads.example.com"},
		{"*.example.com", "*.example.com"},
		{"ads.example.com # tracker", ""}, // inline comments only work on hosts lines
		{"localhost", ""},
		{"example", ""},
		{"1.2.3.4", ""},
		{"2001:db8::1", ""},

		// Kept verbatim: trailing/leading dots add an empty label, so these never match a lookup.
		{"ads.example.com.", "ads.example.com."},
		{"0.0.0.0 ads.example.com.", "ads.example.com."},
		{".example.com", ".example.com"},

		// Comments and cosmetic rules
		{"", ""},
		{"# comment", ""},
		{"! comment", ""},
		{"##.ad-banner", ""},
		{"example.com##.ad", "example.com##.ad"},
	}
	for _, c := range cases {
		if got := parseDomainLine(c.line); got != c.want {
			t.Errorf("parseDomainLine(%q) = %q, want %q", c.line, got, c.want)
		}
	}
}

func FuzzParseDomainLine(f *testing.F) {
	for _, s := range []string{"||ads.example.com^", "0.0.0.0 ads.example.com # c", "*.x.com", "@@||a.b^", "::1 a.b", "1.2.3.4"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, line string) {
		d := parseDomainLine(line)
		if d == "" {
			return
		}
		if !strings.Contains(d, ".") || d != strings.TrimSpace(d) || net.ParseIP(d) != nil {
			t.Fatalf("parseDomainLine(%q) = %q", line, d)
		}
	})
}

func compile(t testing.TB, list string) (*MmapTrie, int) {
	t.Helper()
	dir := t.TempDir()
	in := filepath.Join(dir, "list.txt")
	if err := os.WriteFile(in, []byte(list), 0o600); err != nil {
		t.Fatal(err)
	}
	triePath := filepath.Join(dir, "f.trie")
	n, err := CompileFilterList(in, triePath, filepath.Join(dir, "f.bloom"))
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	tr, err := LoadMmapTrie(triePath)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	t.Cleanup(tr.Close)
	return tr, n
}

func TestCompileLoadRoundTrip(t *testing.T) {
	list := strings.Join([]string{
		"! Title: test list",
		"# hosts section",
		"0.0.0.0 tracker.example.com",
		"127.0.0.1 localhost",
		"||ads.example.org^",
		"||ADS.example.org^", // duplicate after lowercasing
		"@@||allowed.example.net^",
		"metrics.example.net",
		"*.wild.example.io",
		"trailing.example.dev.",
		"",
	}, "\n")
	tr, n := compile(t, list)
	if n != 5 {
		t.Errorf("compiled %d domains, want 5", n)
	}

	cases := map[string]bool{
		"tracker.example.com":          true,
		"a.b.tracker.example.com":      true,
		"ads.example.org":              true,
		"x.ads.example.org":            true,
		"metrics.example.net":          true,
		"deep.sub.metrics.example.net": true,
		"a.wild.example.io":            true,
		"a.b.wild.example.io":          true,
		"wild.example.io":              false, // wildcard needs at least one label
		"example.com":                  false,
		"com":                          false,
		"racker.example.com":           false,
		"nottracker.example.com":       false,
		"tracker.example.co":           false,
		"tracker.example.com.evil":     false,
		"allowed.example.net":          false,
		"localhost":                    false,
		"":                             false,
		"trailing.example.dev":         false, // compiled with an empty label; see TestParseDomainLine
	}
	for d, want := range cases {
		if got := tr.ContainsOrParent(d); got != want {
			t.Errorf("ContainsOrParent(%q) = %v, want %v", d, got, want)
		}
	}
}

func TestCompileFilterListErrors(t *testing.T) {
	dir := t.TempDir()
	if _, err := CompileFilterList(filepath.Join(dir, "missing"), filepath.Join(dir, "t"), filepath.Join(dir, "b")); err == nil {
		t.Error("missing input: expected error")
	}
	empty := filepath.Join(dir, "empty.txt")
	os.WriteFile(empty, []byte("# only comments\n@@||a.b^\n"), 0o600)
	if _, err := CompileFilterList(empty, filepath.Join(dir, "t"), filepath.Join(dir, "b")); err == nil {
		t.Error("no domains: expected error")
	}
	ok := filepath.Join(dir, "ok.txt")
	os.WriteFile(ok, []byte("ads.example.com\n"), 0o600)
	if _, err := CompileFilterList(ok, filepath.Join(dir, "nodir", "t"), filepath.Join(dir, "b")); err == nil {
		t.Error("unwritable trie path: expected error")
	}
	if _, err := CompileFilterList(ok, filepath.Join(dir, "t"), filepath.Join(dir, "nodir", "b")); err == nil {
		t.Error("unwritable bloom path: expected error")
	}
}
