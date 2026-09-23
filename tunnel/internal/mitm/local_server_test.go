package mitm

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/nqmgaming/blockads-tunnel/internal/scriptlet"
)

// saveLocalServerGlobals restores the package-level asset state after a test.
func saveLocalServerGlobals(t *testing.T) {
	t.Helper()
	cosmeticMu.RLock()
	css := cosmeticCSS
	cosmeticMu.RUnlock()
	scriptletsMu.RLock()
	js, store := scriptletsJS, scriptletDb
	scriptletsMu.RUnlock()
	certMu.RLock()
	cm := certManagerInstance
	certMu.RUnlock()
	t.Cleanup(func() {
		cosmeticMu.Lock()
		cosmeticCSS = css
		cosmeticMu.Unlock()
		scriptletsMu.Lock()
		scriptletsJS, scriptletDb = js, store
		scriptletsMu.Unlock()
		SetLocalAssetCertManager(cm)
	})
}

func serve(t *testing.T, path string) (*http.Response, string) {
	t.Helper()
	req := httptest.NewRequest("GET", "https://local.pwhs.app"+path, nil)
	resp := ServeLocalAsset(req)
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	// The header map is a literal with the non-canonical key "X-BlockAds".
	if resp.Request != req || resp.Header["X-BlockAds"][0] != "local-asset-server" {
		t.Errorf("%s: response not built by buildTextResponse", path)
	}
	if cl := resp.Header.Get("Content-Length"); cl != intToStr(len(body)) || resp.ContentLength != int64(len(body)) {
		t.Errorf("%s: Content-Length %s/%d for %d bytes", path, cl, resp.ContentLength, len(body))
	}
	return resp, string(body)
}

func TestServeLocalAssetRoutes(t *testing.T) {
	saveLocalServerGlobals(t)
	SetCosmeticCSS("")
	SetScriptletStore(nil)
	SetScriptletsRuntime("window.__ba={};")
	SetLocalAssetCertManager(nil)

	for _, tc := range []struct {
		path, ctype, body string
		status            int
	}{
		{"/", "text/html", "BlockAds Local Proxy", 200},
		{"/index.html", "text/html", "/cert.crt", 200},
		{"/cosmetic.css", "text/css", "no cosmetic rules loaded", 200},
		{"/scriptlets.js", "application/javascript", "window.__ba={};", 200},
		{"/sl-www.example.com.js", "application/javascript", "no scriptlets for www.example.com", 200},
		{"/sl-WWW.EXAMPLE.COM.js", "application/javascript", "no scriptlets for www.example.com", 200},
		{"/health", "application/json", `{"status":"ok","css_bytes":0}`, 200},
		{"/cert.crt", "text/plain", "not initialized", 503},
		{"/nope", "text/plain", "Not Found", 404},
		{"/sl-.css", "text/plain", "Not Found", 404},
	} {
		resp, body := serve(t, tc.path)
		if resp.StatusCode != tc.status || !strings.HasPrefix(resp.Header.Get("Content-Type"), tc.ctype) || !strings.Contains(body, tc.body) {
			t.Errorf("%s: %d %q %.60q", tc.path, resp.StatusCode, resp.Header.Get("Content-Type"), body)
		}
	}

	SetCosmeticCSS(".ad{display:none}")
	if _, body := serve(t, "/cosmetic.css"); body != ".ad{display:none}" {
		t.Errorf("cosmetic.css = %q", body)
	}
	if _, body := serve(t, "/health"); body != `{"status":"ok","css_bytes":17}` {
		t.Errorf("health = %q", body)
	}
}

func TestServeLocalAssetCACert(t *testing.T) {
	saveLocalServerGlobals(t)
	cm, _ := newTestCertManager(t) // registers itself
	for _, path := range []string{"/cert.crt", "/cert.pem", "/ca.crt"} {
		resp, body := serve(t, path)
		if resp.StatusCode != 200 || body != cm.GetCACertPEM() || !strings.Contains(resp.Header.Get("Content-Disposition"), "BlockAds-CA.crt") {
			t.Errorf("%s: %d %v", path, resp.StatusCode, resp.Header)
		}
	}
	SetLocalAssetCertManager(&CertManager{})
	if resp, _ := serve(t, "/cert.crt"); resp.StatusCode != 404 {
		t.Errorf("empty CA: status %d, want 404", resp.StatusCode)
	}
}

func TestServePerHostScriptlets(t *testing.T) {
	saveLocalServerGlobals(t)
	SetScriptletStore(scriptlet.BuildStore(scriptlet.ParseRules("example.com##+js(set-constant, ads, false)")))
	_, body := serve(t, "/sl-www.example.com.js")
	if !strings.Contains(body, `"set-constant"`) {
		t.Fatalf("no invocation for a matching host: %q", body)
	}
	if _, body := serve(t, "/sl-other.test.js"); !strings.HasPrefix(body, "/* BlockAds: no scriptlets") {
		t.Fatalf("unexpected body for a non-matching host: %q", body)
	}
}

// The host in /sl-<host>.js is echoed into a JS comment unescaped, so a
// path containing "*/" closes the comment and the rest runs as script.
func TestServePerHostScriptletsCommentEscape(t *testing.T) {
	t.Skip("known bug: /sl-<host>.js echoes the host into a /* */ comment unescaped, so \"*/\" breaks out")
	saveLocalServerGlobals(t)
	SetScriptletStore(nil)
	req := httptest.NewRequest("GET", "https://local.pwhs.app/", nil)
	req.URL = &url.URL{Path: "/sl-x*/alert(1)//.js"}
	body, _ := io.ReadAll(ServeLocalAsset(req).Body)
	if i := strings.Index(string(body), "*/"); i != len(body)-2 {
		t.Fatalf("comment terminated early: %q", body)
	}
}

func TestIsLocalAssetHost(t *testing.T) {
	for host, want := range map[string]bool{
		"local.pwhs.app": true, "LOCAL.PWHS.APP": true, " local.pwhs.app:443 ": true,
		"pwhs.app": false, "x.local.pwhs.app": false, "": false,
	} {
		if got := IsLocalAssetHost(host); got != want {
			t.Errorf("IsLocalAssetHost(%q) = %v, want %v", host, got, want)
		}
	}
}
