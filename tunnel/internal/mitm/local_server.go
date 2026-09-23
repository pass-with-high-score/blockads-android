package mitm

import (
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/nqmgaming/blockads-tunnel/internal/scriptlet"
)

// ─────────────────────────────────────────────────────────────────────────────
// Local Asset Server — fake domain "local.pwhs.app"
//
// Instead of injecting thousands of bytes of raw CSS inline into every HTML
// page (which bloats the response and delays rendering), we inject a lightweight
// <link> tag pointing to this fake domain:
//
//   <link rel="stylesheet" href="https://local.pwhs.app/cosmetic.css">
//
// When the browser fetches these URLs through the MITM proxy, the proxy
// recognises the hostname and serves the assets directly from memory — no
// upstream dial, no network round-trip.
//
// Advantages over inline injection:
//   • HTML payload stays small (~80 bytes injected vs 50-100KB inline)
//   • Browser can cache the CSS (304 Not Modified via ETag)
//   • Easier to update rules without re-parsing every page
//   • Separates concerns: injection vs content serving
// ─────────────────────────────────────────────────────────────────────────────

// LocalAssetHost is the fake hostname the proxy intercepts to serve assets.
const LocalAssetHost = "local.pwhs.app"

// ServeLocalAsset handles HTTP requests to local.pwhs.app.
// Returns true if the request was handled (caller should NOT forward upstream).
// Returns false if the path is unknown (caller can 404).
func ServeLocalAsset(req *http.Request) *http.Response {
	path := req.URL.Path

	switch {
	case path == "/" || path == "/index.html":
		return serveIndex(req)
	case path == "/cert.crt" || path == "/cert.pem" || path == "/ca.crt":
		return serveCACert(req)
	case path == "/cosmetic.css":
		return serveCSS(req)
	case path == "/scriptlets.js":
		return serveScriptlets(req)
	case strings.HasPrefix(path, "/sl-") && strings.HasSuffix(path, ".js"):
		return servePerHostScriptlets(req)
	case path == "/health":
		return serveHealth(req)
	default:
		return serve404(req)
	}
}

// serveCSS returns the cosmetic filter CSS from memory.
func serveCSS(req *http.Request) *http.Response {
	cosmeticMu.RLock()
	css := cosmeticCSS
	cosmeticMu.RUnlock()

	if css == "" {
		css = "/* BlockAds: no cosmetic rules loaded */"
	}

	return buildTextResponse(req, 200, "text/css; charset=utf-8", css)
}

// scriptletsJS holds the runtime library injected into pages so that
// per-host scriptlet invocations have something to call. Phase S-A
// ships a placeholder; S-B replaces it with a real runtime that
// implements set-constant, abort-on-property-read, prevent-fetch, etc.
var (
	scriptletsMu sync.RWMutex
	// Default to the S-B runtime; can be overridden via SetScriptletsRuntime.
	scriptletsJS = scriptlet.RuntimeJS
	scriptletDb  *scriptlet.Store
)

// SetScriptletsRuntime replaces the runtime JS served at /scriptlets.js.
// Called from Kotlin once the real @adguard/scriptlets bundle is built
// and available. Phase S-A leaves this empty; S-B wires it.
func SetScriptletsRuntime(js string) {
	scriptletsMu.Lock()
	scriptletsJS = js
	scriptletsMu.Unlock()
	logf("Scriptlets runtime updated: %d bytes", len(js))
}

// SetScriptletStore replaces the scriptlet rule database used to
// generate per-host invocations. Called by the engine after parsing
// filter lists for +js() rules. Passing nil clears the store.
func SetScriptletStore(s *scriptlet.Store) {
	scriptletsMu.Lock()
	scriptletDb = s
	scriptletsMu.Unlock()
	if s != nil {
		logf("Scriptlet store updated: %d global, %d host-bound", len(s.All), len(s.ByHost))
	}
}

// serveScriptlets returns the runtime JS library.
func serveScriptlets(req *http.Request) *http.Response {
	scriptletsMu.RLock()
	js := scriptletsJS
	scriptletsMu.RUnlock()
	return buildTextResponse(req, 200, "application/javascript; charset=utf-8", js)
}

// servePerHostScriptlets returns the host-specific scriptlet invocations.
// Path format: /sl-<host>.js (e.g., /sl-www.youtube.com.js). If no
// rules apply to the host the response is an empty JS comment.
func servePerHostScriptlets(req *http.Request) *http.Response {
	path := req.URL.Path
	host := strings.TrimSuffix(strings.TrimPrefix(path, "/sl-"), ".js")
	host = strings.ToLower(host)

	scriptletsMu.RLock()
	store := scriptletDb
	scriptletsMu.RUnlock()

	js := ""
	if store != nil {
		js = store.BuildHostInvocations(host)
	}
	if js == "" {
		js = "/* BlockAds: no scriptlets for " + host + " */"
	}
	return buildTextResponse(req, 200, "application/javascript; charset=utf-8", js)
}

// serveHealth returns a simple health check (useful for debugging).
func serveHealth(req *http.Request) *http.Response {
	cosmeticMu.RLock()
	cssLen := len(cosmeticCSS)
	cosmeticMu.RUnlock()

	body := fmt.Sprintf(`{"status":"ok","css_bytes":%d}`, cssLen)
	return buildTextResponse(req, 200, "application/json", body)
}

// serve404 returns a 404 for unknown paths.
func serve404(req *http.Request) *http.Response {
	return buildTextResponse(req, 404, "text/plain", "Not Found")
}

// buildTextResponse creates an *http.Response with the given status, content-type, and body.
func buildTextResponse(req *http.Request, status int, contentType, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Status:     fmt.Sprintf("%d %s", status, http.StatusText(status)),
		Proto:      "HTTP/1.1",
		ProtoMajor: 1,
		ProtoMinor: 1,
		Header: http.Header{
			"Content-Type":                []string{contentType},
			"Content-Length":              []string{fmt.Sprintf("%d", len(body))},
			"Cache-Control":              []string{"public, max-age=300"}, // 5min cache
			"Access-Control-Allow-Origin": []string{"*"},
			"X-BlockAds":                 []string{"local-asset-server"},
		},
		Body:          readCloserFromString(body),
		ContentLength: int64(len(body)),
		Request:       req,
	}
}

// IsLocalAssetHost returns true if the given hostname matches the local asset server.
func IsLocalAssetHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	// Strip port if present
	if idx := strings.LastIndex(h, ":"); idx != -1 {
		h = h[:idx]
	}
	return h == LocalAssetHost
}

// readCloserFromString wraps a string in an io.ReadCloser.
func readCloserFromString(s string) readCloserStr {
	return readCloserStr{strings.NewReader(s)}
}

type readCloserStr struct {
	*strings.Reader
}

func (readCloserStr) Close() error { return nil }

var (
	certManagerInstance *CertManager
	certMu              sync.RWMutex
)

// SetLocalAssetCertManager records the CertManager instance for local asset serving.
func SetLocalAssetCertManager(cm *CertManager) {
	certMu.Lock()
	certManagerInstance = cm
	certMu.Unlock()
}

// serveCACert returns the Root CA certificate for easy device installation.
func serveCACert(req *http.Request) *http.Response {
	certMu.RLock()
	cm := certManagerInstance
	certMu.RUnlock()

	if cm == nil {
		return buildTextResponse(req, 503, "text/plain; charset=utf-8", "BlockAds Root CA not initialized yet")
	}

	pemStr := cm.GetCACertPEM()
	if pemStr == "" {
		return buildTextResponse(req, 404, "text/plain; charset=utf-8", "No CA certificate available")
	}

	resp := buildTextResponse(req, 200, "application/x-x509-ca-cert", pemStr)
	resp.Header.Set("Content-Disposition", `attachment; filename="BlockAds-CA.crt"`)
	return resp
}

// serveIndex returns a local dashboard page showing protection status and CA certificate link.
func serveIndex(req *http.Request) *http.Response {
	html := `<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>BlockAds Local Protection</title>
<style>
:root { --bg: #0b0f19; --card: #151d30; --primary: #3b82f6; --text: #f3f4f6; --text-muted: #9ca3af; --green: #10b981; }
* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
body { background: var(--bg); color: var(--text); display: flex; justify-content: center; align-items: center; min-height: 100vh; padding: 1.5rem; }
.card { background: var(--card); border: 1px solid rgba(255,255,255,0.08); border-radius: 1.5rem; max-width: 480px; width: 100%; padding: 2rem; box-shadow: 0 20px 40px rgba(0,0,0,0.5); text-align: center; }
.shield { width: 64px; height: 64px; background: rgba(16, 185, 129, 0.15); color: var(--green); border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 0 auto 1.25rem; }
.shield svg { width: 36px; height: 36px; fill: currentColor; }
h1 { font-size: 1.5rem; margin-bottom: 0.5rem; font-weight: 700; }
p { color: var(--text-muted); font-size: 0.95rem; line-height: 1.5; margin-bottom: 1.5rem; }
.badge { display: inline-flex; align-items: center; gap: 0.5rem; background: rgba(16, 185, 129, 0.12); color: var(--green); padding: 0.4rem 1rem; border-radius: 2rem; font-weight: 600; font-size: 0.85rem; margin-bottom: 1.5rem; }
.badge-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--green); }
.btn { display: inline-flex; align-items: center; justify-content: center; gap: 0.5rem; width: 100%; background: var(--primary); color: #fff; text-decoration: none; padding: 0.85rem 1.25rem; border-radius: 0.85rem; font-weight: 600; font-size: 1rem; transition: filter 0.2s; }
.btn:hover { filter: brightness(1.15); }
.info { text-align: left; background: rgba(255,255,255,0.03); border-radius: 0.85rem; padding: 1rem; margin-top: 1.5rem; font-size: 0.85rem; color: var(--text-muted); border-left: 3px solid var(--primary); }
</style>
</head>
<body>
<div class="card">
<div class="shield">
<svg viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16l-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z"/></svg>
</div>
<div class="badge"><span class="badge-dot"></span> Đang hoạt động</div>
<h1>BlockAds Local Proxy</h1>
<p>HTTPS Filtering & DNS Shield đang chạy cục bộ trên máy. 100% dữ liệu được lọc nội bộ, không telemetry, không gửi dữ liệu ra bên ngoài.</p>
<a href="/cert.crt" class="btn">Tải Chứng Chỉ Root CA (BlockAds-CA.crt)</a>
<div class="info">
<strong>Hướng dẫn cài đặt:</strong><br>
1. Tải chứng chỉ về máy.<br>
2. Vào Cài đặt → Bảo mật & quyền riêng tư → Thông tin xác thực → Cài đặt chứng chỉ CA.
</div>
</div>
</body>
</html>`
	return buildTextResponse(req, 200, "text/html; charset=utf-8", html)
}

// ── Pre-generate cert for local.pwhs.app at proxy startup ────────────────

// WarmLocalAssetCert pre-generates the TLS certificate for local.pwhs.app
// so the first request doesn't incur cert generation latency.
func (cm *CertManager) WarmLocalAssetCert() {
	SetLocalAssetCertManager(cm)
	start := time.Now()
	_, err := cm.getCertForHost(LocalAssetHost)
	if err != nil {
		logf("Local asset server: cert pre-gen failed: %v", err)
	} else {
		logf("Local asset server: cert for %s pre-generated in %v", LocalAssetHost, time.Since(start))
	}
}

