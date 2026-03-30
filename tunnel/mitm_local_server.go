package tunnel

import (
	"fmt"
	"net/http"
	"strings"
	"time"
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
	case path == "/cosmetic.css":
		return serveCSS(req)
	case path == "/health":
		return serveHealth(req)
	default:
		return serve404(req)
	}
}

// serveCSS returns the cosmetic filter CSS from memory based on the domain.
func serveCSS(req *http.Request) *http.Response {
	domain := req.URL.Query().Get("domain")

	cosmeticMu.RLock()
	globalCSS := bakedGlobalCSS
	complex := complexRules
	cosmeticMu.RUnlock()

	var css strings.Builder
	css.Grow(len(globalCSS) + 4096)
	css.WriteString(globalCSS)

	for _, r := range complex {
		apply := false
		if len(r.included) == 0 {
			apply = true
		} else {
			for _, inc := range r.included {
				if matchDomain(inc, domain) {
					apply = true
					break
				}
			}
		}

		if apply && len(r.excluded) > 0 {
			for _, exc := range r.excluded {
				if matchDomain(exc, domain) {
					apply = false
					break
				}
			}
		}

		if apply {
			css.WriteString(r.selector)
			css.WriteString(" { display: none !important; }\n")
		}
	}

	res := css.String()
	if res == "" {
		res = "/* BlockAds: no cosmetic rules loaded for this domain */"
	}

	return buildTextResponse(req, 200, "text/css; charset=utf-8", res)
}

func matchDomain(ruleDom, reqDom string) bool {
	if ruleDom == "" || reqDom == "" {
		return false
	}
	if ruleDom == reqDom {
		return true
	}
	if strings.HasSuffix(reqDom, "."+ruleDom) {
		return true
	}
	return false
}

// serveHealth returns a simple health check (useful for debugging).
func serveHealth(req *http.Request) *http.Response {
	cosmeticMu.RLock()
	cssLen := len(bakedGlobalCSS)
	complexLen := len(complexRules)
	cosmeticMu.RUnlock()

	body := fmt.Sprintf(`{"status":"ok","global_css_bytes":%d,"complex_rules":%d}`, cssLen, complexLen)
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

// ── Pre-generate cert for local.pwhs.app at proxy startup ────────────────

// WarmLocalAssetCert pre-generates the TLS certificate for local.pwhs.app
// so the first request doesn't incur cert generation latency.
func (cm *CertManager) WarmLocalAssetCert() {
	start := time.Now()
	_, err := cm.getCertForHost(LocalAssetHost)
	if err != nil {
		logf("Local asset server: cert pre-gen failed: %v", err)
	} else {
		logf("Local asset server: cert for %s pre-generated in %v", LocalAssetHost, time.Since(start))
	}
}
