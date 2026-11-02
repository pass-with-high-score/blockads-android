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
	if req.Method == http.MethodOptions {
		return &http.Response{
			StatusCode: http.StatusNoContent,
			Status:     "204 No Content",
			Proto:      "HTTP/1.1",
			ProtoMajor: 1,
			ProtoMinor: 1,
			Header: http.Header{
				"Access-Control-Allow-Origin":          []string{"*"},
				"Access-Control-Allow-Methods":         []string{"GET, HEAD, OPTIONS"},
				"Access-Control-Allow-Headers":         []string{"*"},
				"Access-Control-Allow-Private-Network": []string{"true"},
				"Access-Control-Max-Age":               []string{"86400"},
				"X-BlockAds":                           []string{"local-asset-server"},
			},
			ContentLength: 0,
			Request:       req,
		}
	}

	path := req.URL.Path

	switch {
	case path == "/" || path == "/index.html":
		return serveIndex(req)
	case path == "/logo.svg" || path == "/favicon.svg":
		return serveLogo(req)
	case path == "/cert.crt" || path == "/cert.pem" || path == "/ca.crt":
		return serveCACert(req)
	case path == "/cosmetic.css":
		return serveCSS(req)
	case strings.HasPrefix(path, "/cs-") && strings.HasSuffix(path, ".css"):
		return servePerHostCSS(req)
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

// serveCSS returns the generic cosmetic filter CSS from memory.
func serveCSS(req *http.Request) *http.Response {
	css := defaultCosmeticStore.GetGenericCSS()
	if css == "" {
		cosmeticMu.RLock()
		css = cosmeticCSS
		cosmeticMu.RUnlock()
	}

	if css == "" {
		css = "/* BlockAds: no cosmetic rules loaded */"
	}

	return buildTextResponse(req, 200, "text/css; charset=utf-8", css)
}

// servePerHostCSS returns cosmetic CSS specific to the requested hostname.
func servePerHostCSS(req *http.Request) *http.Response {
	path := req.URL.Path
	host := strings.TrimSuffix(strings.TrimPrefix(path, "/cs-"), ".css")
	host = strings.ToLower(host)

	css := defaultCosmeticStore.GetCSSForHost(host)
	if modCSS := ActiveModuleStore.GetCosmeticCSS(host); len(modCSS) > 0 {
		var b strings.Builder
		if css != "" {
			b.WriteString(css)
			b.WriteString("\n")
		}
		b.WriteString("/* Module CSS for " + host + " */\n")
		for _, rule := range modCSS {
			b.WriteString(rule)
			b.WriteString("\n")
		}
		css = b.String()
	}

	if css == "" {
		css = "/* BlockAds: no custom CSS for " + host + " */"
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

	var jsBuilder strings.Builder
	if store != nil {
		if inv := store.BuildHostInvocations(host); inv != "" {
			jsBuilder.WriteString(inv)
			jsBuilder.WriteString("\n")
		}
	}

	// Append domain-specific scripts declared in RuleManifest
	customScripts := defaultManifest.GetDomainScripts(host)
	for _, cs := range customScripts {
		if strings.TrimSpace(cs) != "" {
			jsBuilder.WriteString("\n// [RuleManifest] custom script\n")
			jsBuilder.WriteString(cs)
			jsBuilder.WriteString("\n")
		}
	}

	// Append domain-specific scripts declared in ActiveModuleStore
	moduleScripts := ActiveModuleStore.GetScripts(host)
	for _, ms := range moduleScripts {
		if strings.TrimSpace(ms) != "" {
			jsBuilder.WriteString("\n// [WebModule] custom script\n")
			jsBuilder.WriteString(ms)
			jsBuilder.WriteString("\n")
		}
	}

	js := jsBuilder.String()
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
			"Content-Type":                        []string{contentType},
			"Content-Length":                      []string{fmt.Sprintf("%d", len(body))},
			"Cache-Control":                       []string{"public, max-age=300"}, // 5min cache
			"Access-Control-Allow-Origin":         []string{"*"},
			"Access-Control-Allow-Methods":        []string{"GET, HEAD, OPTIONS"},
			"Access-Control-Allow-Headers":        []string{"*"},
			"Access-Control-Allow-Private-Network": []string{"true"},
			"X-BlockAds":                          []string{"local-asset-server"},
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

// serveLogo returns the official BlockAds SVG launcher icon.
func serveLogo(req *http.Request) *http.Response {
	return buildTextResponse(req, 200, "image/svg+xml; charset=utf-8", blockAdsLogoSVG)
}

// serveIndex returns a local dashboard page showing protection status, official app logo, and CA certificate link.
func serveIndex(req *http.Request) *http.Response {
	lang := resolveLocalAssetLang(req)
	tr, ok := localTranslations[lang]
	if !ok {
		tr = localTranslations["en"]
	}

	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="%s" dir="%s">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>BlockAds Local Proxy</title>
<link rel="icon" type="image/svg+xml" href="/logo.svg">
<style>
:root { --bg: #080b12; --card: #111726; --brand: #00e676; --brand-dark: #00c853; --text: #f3f4f6; --text-muted: #9ca3af; }
* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
body { background: radial-gradient(circle at 50%% 0%%, rgba(0,230,118,0.12), transparent 60%%), var(--bg); color: var(--text); display: flex; justify-content: center; align-items: center; min-height: 100vh; padding: 1.5rem; }
.card { background: var(--card); border: 1px solid rgba(0,230,118,0.18); border-radius: 1.75rem; max-width: 480px; width: 100%%; padding: 2.25rem 1.85rem; box-shadow: 0 24px 48px rgba(0,0,0,0.65); text-align: center; position: relative; }
.app-logo { width: 84px; height: 84px; margin: 0 auto 1rem; border-radius: 20px; box-shadow: 0 10px 28px rgba(0,230,118,0.22); overflow: hidden; display: flex; align-items: center; justify-content: center; }
.brand-row { display: inline-flex; align-items: center; gap: 0.45rem; font-size: 0.8rem; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase; color: var(--brand); margin-bottom: 0.4rem; }
.verified { width: 15px; height: 15px; fill: var(--brand); }
h1 { font-size: 1.55rem; margin-bottom: 0.25rem; font-weight: 800; letter-spacing: -0.01em; }
.subtitle { color: var(--text-muted); font-size: 0.85rem; font-weight: 500; margin-bottom: 1rem; }
.badge { display: inline-flex; align-items: center; gap: 0.5rem; background: rgba(0, 230, 118, 0.12); border: 1px solid rgba(0, 230, 118, 0.28); color: var(--brand); padding: 0.4rem 1rem; border-radius: 2rem; font-weight: 600; font-size: 0.85rem; margin-bottom: 1.25rem; }
.badge-dot { width: 8px; height: 8px; border-radius: 50%%; background: var(--brand); box-shadow: 0 0 8px var(--brand); }
p { color: var(--text-muted); font-size: 0.94rem; line-height: 1.55; margin-bottom: 1.5rem; }
.btn { display: inline-flex; align-items: center; justify-content: center; gap: 0.5rem; width: 100%%; background: linear-gradient(135deg, var(--brand), var(--brand-dark)); color: #051b0e; text-decoration: none; padding: 0.9rem 1.25rem; border-radius: 0.9rem; font-weight: 700; font-size: 0.96rem; box-shadow: 0 8px 20px rgba(0,230,118,0.25); transition: transform 0.15s, filter 0.2s; }
.btn:hover { filter: brightness(1.08); transform: translateY(-1px); }
.info { text-align: start; background: rgba(255,255,255,0.03); border-radius: 0.9rem; padding: 1rem 1.15rem; margin-top: 1.5rem; font-size: 0.85rem; line-height: 1.55; color: var(--text-muted); border-inline-start: 3px solid var(--brand); }
.info strong { color: var(--text); display: block; margin-bottom: 0.3rem; }
.footer { margin-top: 1.35rem; font-size: 0.76rem; color: rgba(156,163,175,0.75); font-weight: 500; }
</style>
</head>
<body>
<div class="card">
<div class="app-logo">%s</div>
<div class="brand-row">
<span>BlockAds Official</span>
<svg class="verified" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16l-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z"/></svg>
</div>
<h1>BlockAds Local Proxy</h1>
<div class="subtitle">%s</div>
<div class="badge"><span class="badge-dot"></span> %s</div>
<p>%s</p>
<a href="/cert.crt" class="btn">%s</a>
<div class="info">
<strong>%s</strong>
%s<br>
%s
</div>
<div class="footer">%s</div>
</div>
</body>
</html>`,
		tr.Lang,
		tr.Dir,
		blockAdsLogoSVG,
		tr.Subtitle,
		tr.BadgeActive,
		tr.Description,
		tr.DownloadBtn,
		tr.GuideTitle,
		tr.Step1,
		tr.Step2,
		tr.FooterNote,
	)
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

