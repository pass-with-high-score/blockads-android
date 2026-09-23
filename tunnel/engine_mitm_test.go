package tunnel

import (
	"io"
	"net"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// localAsset fetches path from the in-process local.pwhs.app server.
func localAsset(t *testing.T, path string) string {
	t.Helper()
	resp := ServeLocalAsset(httptest.NewRequest("GET", "https://"+LocalAssetHost+path, nil))
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func resetMitmGlobals(t *testing.T) {
	t.Cleanup(func() {
		SetCosmeticCSS("")
		SetScriptletStore(nil)
		SetScriptletsRuntime(scriptletRuntimeJS)
	})
}

// Every stack-MITM setter is a logged no-op until StartStackMitm has run.
func TestMitmSettersBeforeStart(t *testing.T) {
	e := NewEngine()
	if e.IsMitmActive() {
		t.Fatal("IsMitmActive on a fresh engine")
	}
	e.SetMitmAllowedUIDs("10100")
	e.SetExtraPassthroughSuffixes("example.com")
	e.SetAdPathPatterns("/ads")
	if e.stackMitmFilter != nil {
		t.Error("setters created a filter without StartStackMitm")
	}
	if got := e.GetMitmCACert(t.TempDir()); got != "" {
		t.Errorf("GetMitmCACert with no CA on disk = %q", got)
	}
}

func TestStartStackMitmLifecycle(t *testing.T) {
	dir := t.TempDir()
	e := NewEngine()
	pem := e.StartStackMitm(dir)
	if !strings.Contains(pem, "BEGIN CERTIFICATE") {
		t.Fatalf("StartStackMitm returned %q", pem)
	}
	if !e.IsMitmActive() {
		t.Error("IsMitmActive = false after StartStackMitm")
	}
	if got := e.GetMitmCACert("/nonexistent"); got != pem {
		t.Error("GetMitmCACert does not return the active CA")
	}

	e.SetMitmAllowedUIDs(" 10100, x ,10200,,0, 1a2 ")
	f := e.stackMitmFilter
	for uid, want := range map[int]bool{10100: true, 10200: true, 12: true, 0: false, 10300: false} {
		if got := f.IsUIDAllowed(uid); got != want {
			t.Errorf("IsUIDAllowed(%d) = %v, want %v", uid, got, want)
		}
	}

	e.SetAdPathPatterns("# comment\n/pagead\n\n  /ads.js  \n")
	if !f.IsAdPathBlocked("/pagead/x") || !f.IsAdPathBlocked("/lib/ads.js") || f.IsAdPathBlocked("/comment") {
		t.Error("ad path patterns not applied")
	}
	if !f.IsInterceptionAllowed("shop.example.org") {
		t.Fatal("shop.example.org is not interceptable before the passthrough list")
	}
	e.SetExtraPassthroughSuffixes("# pinned\nexample.org\n")
	if f.IsInterceptionAllowed("shop.example.org") {
		t.Error("passthrough suffix not applied")
	}

	// A second StartStackMitm keeps the filter and reuses the persisted CA.
	if again := e.StartStackMitm(dir); again != pem {
		t.Error("StartStackMitm regenerated the CA")
	}
	if e.stackMitmFilter != f {
		t.Error("StartStackMitm replaced the existing filter")
	}

	e.StopStackMitm()
	if e.IsMitmActive() {
		t.Error("IsMitmActive after StopStackMitm")
	}
	if got := e.GetMitmCACert(dir); got != pem {
		t.Error("GetMitmCACert does not read the CA from disk")
	}
}

// An unwritable cert dir only costs persistence: the session still gets an
// in-memory CA (NewCertManager logs the save failure and carries on).
func TestStartStackMitmUnwritableDir(t *testing.T) {
	file := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(file, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(file, "sub")
	e := NewEngine()
	if got := e.StartStackMitm(dir); !strings.Contains(got, "BEGIN CERTIFICATE") {
		t.Fatalf("StartStackMitm = %q", got)
	}
	if !e.IsMitmActive() {
		t.Error("IsMitmActive = false")
	}
	e.StopStackMitm()
	if got := e.GetMitmCACert(dir); got != "" {
		t.Error("CA reported as persisted in an unwritable dir")
	}
}

func TestGetMitmCACertUnreadable(t *testing.T) {
	dir := t.TempDir()
	// A directory where the cert file should be: it exists but can't be read.
	if err := os.Mkdir(filepath.Join(dir, caCertFile), 0o700); err != nil {
		t.Fatal(err)
	}
	if got := NewEngine().GetMitmCACert(dir); got != "" {
		t.Errorf("GetMitmCACert = %q, want empty", got)
	}
}

func TestLocalAssetSetters(t *testing.T) {
	resetMitmGlobals(t)
	e := NewEngine()

	e.SetCosmeticCSS(".ad{display:none}")
	if got := localAsset(t, "/cosmetic.css"); !strings.Contains(got, ".ad{display:none}") {
		t.Errorf("cosmetic.css = %q", got)
	}

	e.SetScriptletsRuntime("window.custom=1")
	got := localAsset(t, "/scriptlets.js")
	if !strings.HasPrefix(got, scriptletRuntimeJS) || !strings.HasSuffix(got, "window.custom=1") {
		t.Errorf("scriptlets.js does not append the custom runtime (len %d)", len(got))
	}
	e.SetScriptletsRuntime("")
	if got := localAsset(t, "/scriptlets.js"); got != scriptletRuntimeJS {
		t.Error("empty runtime did not restore the built-in one")
	}

	e.SetScriptletRules("example.com##+js(set-constant, adsOn, false)\n##.not-a-scriptlet\n")
	if got := localAsset(t, "/sl-example.com.js"); !strings.Contains(got, "adsOn") {
		t.Errorf("per-host scriptlets = %q", got)
	}
	e.SetScriptletRules("##.only-cosmetic\n")
	if got := localAsset(t, "/sl-example.com.js"); strings.Contains(got, "adsOn") {
		t.Error("input without scriptlets did not clear the store")
	}
	e.SetScriptletRules("example.com##+js(set-constant, adsOn, false)")
	e.SetScriptletRules("")
	if got := localAsset(t, "/sl-example.com.js"); strings.Contains(got, "adsOn") {
		t.Error("empty input did not clear the store")
	}
}

// IsDomainBlocked mirrors the DNS pipeline: DoH list, custom allow/block,
// security trie, ad trie, then the Kotlin checker.
func TestIsDomainBlocked(t *testing.T) {
	adTrie, adBloom := compileTrie(t, "ads", "ads.example.com")
	secTrie, secBloom := compileTrie(t, "sec", "malware.example.net")
	e := NewEngine()
	e.SetTries(adTrie+", ,"+adTrie, secTrie, adBloom, secBloom)
	defer e.Stop()
	e.SetDoHBlocklist("dns.google")
	e.SetDomainChecker(&fakeChecker{
		custom:  map[string]int{"allowed.ads.example.com": 0, "custom.example.com": 1},
		blocked: map[string]bool{"kotlin.example.com": true},
	})
	for host, want := range map[string]bool{
		"":                        false,
		"  ":                      false,
		"DNS.Google":              true,
		"x.dns.google":            true,
		"allowed.ads.example.com": false,
		"custom.example.com":      true,
		"sub.malware.example.net": true,
		"ADS.example.com ":        true,
		"kotlin.example.com":      true,
		"clean.example.com":       false,
	} {
		if got := e.IsDomainBlocked(host); got != want {
			t.Errorf("IsDomainBlocked(%q) = %v, want %v", host, got, want)
		}
	}

	// Tries without bloom filters are consulted directly.
	e.SetTries(adTrie, secTrie, "", "")
	e.SetDomainChecker(nil)
	if !e.IsDomainBlocked("ads.example.com") || !e.IsDomainBlocked("malware.example.net") {
		t.Error("trie match without a bloom filter")
	}
}

func TestLookupIPExported(t *testing.T) {
	if _, err := NewEngine().LookupIP("example.com"); err == nil {
		t.Error("LookupIP without a resolver succeeded")
	}
	up := startUpstream(t, answerWith("A 192.0.2.7"))
	h := newTunHarness(t, up.addr)
	ip, err := h.e.LookupIP("example.com")
	if err != nil || !ip.Equal(net.IPv4(192, 0, 2, 7)) {
		t.Errorf("LookupIP = %v, %v", ip, err)
	}
}
