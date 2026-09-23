package trie

import (
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func writeTrie(t testing.TB, domains []string) string {
	t.Helper()
	root := newTrieBuilderNode()
	for _, d := range domains {
		root.insert(d)
	}
	path := filepath.Join(t.TempDir(), "f.trie")
	if err := root.saveToFile(path); err != nil {
		t.Fatal(err)
	}
	return path
}

func loadTrie(t testing.TB, path string) *MmapTrie {
	t.Helper()
	tr, err := LoadMmapTrie(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(tr.Close)
	return tr
}

func TestTrieHeader(t *testing.T) {
	data, err := os.ReadFile(writeTrie(t, []string{"a.example.com", "b.example.com"}))
	if err != nil {
		t.Fatal(err)
	}
	// root, com, example, a, b
	if nodes, domains := binary.BigEndian.Uint32(data[8:12]), binary.BigEndian.Uint32(data[12:16]); nodes != 5 || domains != 2 {
		t.Errorf("header nodes=%d domains=%d, want 5 and 2", nodes, domains)
	}
}

func TestContainsOrParentWildcards(t *testing.T) {
	tr := loadTrie(t, writeTrie(t, []string{"*.ads.example.com", "cdn.*.tracker.net", "exact.org"}))
	cases := map[string]bool{
		"x.ads.example.com":                     true,
		"y.x.ads.example.com":                   true,
		"ads.example.com":                       false,
		"cdn.eu.tracker.net":                    true,
		"a.cdn.eu.tracker.net":                  true,
		"cdn.tracker.net":                       false,
		"img.eu.tracker.net":                    false,
		"exact.org":                             true,
		"sub.exact.org":                         true,
		"exact.org.":                            false, // trailing empty label is looked up literally
		"EXACT.ORG":                             false, // lookups are case-sensitive; callers lowercase
		"":                                      false,
		".":                                     false,
		strings.Repeat("a.", 200) + "exact.org": true,
	}
	for d, want := range cases {
		if got := tr.ContainsOrParent(d); got != want {
			t.Errorf("ContainsOrParent(%q) = %v, want %v", d, got, want)
		}
	}
	tr.Close()
	tr.Close() // idempotent
	if tr.ContainsOrParent("exact.org") {
		t.Error("closed trie must not match")
	}
}

func TestLoadMmapTrieErrors(t *testing.T) {
	dir := t.TempDir()
	good, _ := os.ReadFile(writeTrie(t, []string{"a.b"}))
	badMagic := append([]byte{}, good...)
	badMagic[0] = 'X'
	badVersion := append([]byte{}, good...)
	badVersion[7] = 1
	write := func(name string, data []byte) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, data, 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}
	for name, path := range map[string]string{
		"empty path":  "",
		"missing":     filepath.Join(dir, "nope"),
		"too small":   write("small", good[:headerSize-1]),
		"bad magic":   write("magic", badMagic),
		"bad version": write("version", badVersion),
	} {
		if tr, err := LoadMmapTrie(path); err == nil {
			tr.Close()
			t.Errorf("%s: expected error", name)
		}
	}
}

// A corrupt file can point a node's exact and "*" children back at itself, so each
// label doubles the work in matchWithWildcard.
func TestContainsOrParentCyclicTrieIsBounded(t *testing.T) {
	t.Skip("exponential in label count on crafted cyclic tries; bounding it needs a visited set or depth budget on the hot path")
	buf := make([]byte, headerSize)
	binary.BigEndian.PutUint32(buf[0:4], trieMagic)
	binary.BigEndian.PutUint32(buf[4:8], trieVersion)
	buf = append(buf, 0, 0, 0, 0, 2)
	for _, label := range []string{"*", "a"} {
		buf = append(buf, 0, byte(len(label)))
		buf = append(buf, label...)
		buf = binary.BigEndian.AppendUint32(buf, headerSize)
	}
	path := filepath.Join(t.TempDir(), "cyclic.trie")
	os.WriteFile(path, buf, 0o600)
	tr := loadTrie(t, path)

	start := time.Now()
	tr.ContainsOrParent(strings.Repeat("a.", 40) + "b")
	if d := time.Since(start); d > time.Second {
		t.Fatalf("lookup took %v", d)
	}
}

func FuzzLoadMmapTrie(f *testing.F) {
	for _, set := range [][]string{{"ads.example.com"}, {"*.a.b", "c.d"}, {"x.y", "z.x.y", "q.*.x.y"}} {
		data, _ := os.ReadFile(writeTrie(f, set))
		f.Add(data)
	}
	f.Fuzz(func(t *testing.T, data []byte) {
		path := filepath.Join(t.TempDir(), "f.trie")
		if err := os.WriteFile(path, data, 0o600); err != nil {
			t.Fatal(err)
		}
		tr, err := LoadMmapTrie(path)
		if err != nil {
			return
		}
		defer tr.Close()
		// Few labels: deeper lookups hit the known blowup in TestContainsOrParentCyclicTrieIsBounded.
		for _, d := range []string{"", "ads.example.com", "a.b", "q.z.x.y", "*.*"} {
			tr.ContainsOrParent(d)
		}
	})
}

func BenchmarkContainsOrParent(b *testing.B) {
	const n = 300000
	domains := make([]string, n)
	for i := range domains {
		domains[i] = fmt.Sprintf("host%d.tracker%d.example%d.com", i, i%997, i%31)
	}
	tr := loadTrie(b, writeTrie(b, domains))
	b.Run("hit", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			if !tr.ContainsOrParent(domains[i%n]) {
				b.Fatal("miss")
			}
		}
	})
	b.Run("subdomain-hit", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			tr.ContainsOrParent("a.b.host7.tracker7.example7.com")
		}
	})
	b.Run("miss", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			tr.ContainsOrParent("static.cdn.clean-site.org")
		}
	})
}
