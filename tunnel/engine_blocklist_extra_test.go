package tunnel

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// tempFile writes content to a file in t.TempDir and returns it opened.
func tempFile(t *testing.T, content string) *os.File {
	t.Helper()
	path := filepath.Join(t.TempDir(), "f")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { f.Close() })
	return f
}

// Asset file descriptors point into the APK at arbitrary offsets, so mmapFd
// has to page-align the mapping and hand back only the requested window.
func TestMmapFdUnalignedWindow(t *testing.T) {
	page := os.Getpagesize()
	content := strings.Repeat("x", page+100) + "cloudflare-dns.com\ndns.google\n" + strings.Repeat("y", 50)
	f := tempFile(t, content)

	off := int64(page + 100)
	length := int64(len("cloudflare-dns.com\ndns.google\n"))
	data, cleanup, err := mmapFd(int(f.Fd()), off, length)
	if err != nil {
		t.Fatalf("mmapFd: %v", err)
	}
	if string(data) != content[off:off+length] {
		t.Errorf("window = %q", data)
	}
	cleanup()

	e := NewEngine()
	if e.IsDoHBlockingEnabled() {
		t.Error("DoH blocking enabled before any list")
	}
	if err := e.SetDoHBlocklistFromFd(int(f.Fd()), off, length); err != nil {
		t.Fatalf("SetDoHBlocklistFromFd: %v", err)
	}
	if !e.IsDoHBlockingEnabled() || !e.isDoHDomain("a.dns.google") || e.isDoHDomain("xcloudflare-dns.com") {
		t.Error("windowed DoH list not applied")
	}
}

func TestMmapFdErrors(t *testing.T) {
	data, cleanup, err := mmapFd(-1, 0, 0)
	if err != nil || data != nil || cleanup == nil {
		t.Errorf("zero length: data=%v err=%v cleanup nil=%v", data, err, cleanup == nil)
	}
	cleanup()
	if _, _, err := mmapFd(-1, 0, 10); err == nil {
		t.Error("bad fd mapped")
	}
	e := NewEngine()
	if err := e.SetDoHBlocklistFromFd(-1, 0, 10); err == nil {
		t.Error("SetDoHBlocklistFromFd(bad fd) succeeded")
	}
	if err := e.SetExtraPassthroughSuffixesFromFd(-1, 0, 10); err == nil {
		t.Error("SetExtraPassthroughSuffixesFromFd(bad fd) succeeded")
	}
}

func TestSetExtraPassthroughSuffixesFromFd(t *testing.T) {
	content := "# pinned apps\n\n// also a comment\nzzqq.example\n"
	f := tempFile(t, content)

	e := NewEngine()
	if err := e.SetExtraPassthroughSuffixesFromFd(int(f.Fd()), 0, int64(len(content))); err == nil || !strings.Contains(err.Error(), "not active") {
		t.Errorf("without stack MITM: err = %v", err)
	}

	filter := NewMitmFilter()
	e.mu.Lock()
	e.stackMitmFilter = filter
	e.mu.Unlock()
	before := filter.IsInterceptionAllowed("www.zzqq.example")
	if err := e.SetExtraPassthroughSuffixesFromFd(int(f.Fd()), 0, int64(len(content))); err != nil {
		t.Fatalf("SetExtraPassthroughSuffixesFromFd: %v", err)
	}
	if !before || filter.IsInterceptionAllowed("www.zzqq.example") {
		t.Errorf("interception allowed before=%v after=%v, want true then false", before, filter.IsInterceptionAllowed("www.zzqq.example"))
	}
}

func TestSetCosmeticCSSFromFileErrors(t *testing.T) {
	e := NewEngine()
	if err := e.SetCosmeticCSSFromFile(""); err != nil {
		t.Errorf("empty path: %v", err)
	}
	if err := e.SetCosmeticCSSFromFile(filepath.Join(t.TempDir(), "missing.css")); err == nil {
		t.Error("missing file succeeded")
	}
}

func TestSetScriptletRulesFromFile(t *testing.T) {
	e := NewEngine()
	defer SetScriptletStore(nil)
	dir := t.TempDir()
	write := func(name, content string) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}

	if err := e.SetScriptletRulesFromFile(""); err != nil {
		t.Errorf("empty path: %v", err)
	}
	if err := e.SetScriptletRulesFromFile(filepath.Join(dir, "missing")); err == nil {
		t.Error("missing file succeeded")
	}
	if err := e.SetScriptletRulesFromFile(write("none.txt", "! nothing here\nexample.com##.ad\n")); err != nil {
		t.Errorf("no rules: %v", err)
	}
	if err := e.SetScriptletRulesFromFile(write("rules.txt", "example.com##+js(noeval)\n")); err != nil {
		t.Errorf("rules: %v", err)
	}
	if !strings.Contains(scriptletRuntimeJS, "window.__ba") {
		t.Error("runtime alias not wired")
	}
}
