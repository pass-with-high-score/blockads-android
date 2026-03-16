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
// Instead of injecting thousands of bytes of raw CSS/JS inline into every HTML
// page (which bloats the response and delays rendering), we inject lightweight
// <link> and <script> tags pointing to this fake domain:
//
//   <link rel="stylesheet" href="https://local.pwhs.app/cosmetic.css">
//   <script src="https://local.pwhs.app/killer.js"></script>
//
// When the browser fetches these URLs through the MITM proxy, the proxy
// recognises the hostname and serves the assets directly from memory — no
// upstream dial, no network round-trip.
//
// Advantages over inline injection:
//   • HTML payload stays small (~120 bytes injected vs 50-100KB inline)
//   • Browser can cache the CSS/JS (304 Not Modified via ETag)
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
	case path == "/killer.js":
		return serveJS(req)
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

// serveJS returns the popup-killer JavaScript from memory.
func serveJS(req *http.Request) *http.Response {
	return buildTextResponse(req, 200, "application/javascript; charset=utf-8", popupKillerJS)
}

// serveHealth returns a simple health check (useful for debugging).
func serveHealth(req *http.Request) *http.Response {
	cosmeticMu.RLock()
	cssLen := len(cosmeticCSS)
	cosmeticMu.RUnlock()

	body := fmt.Sprintf(`{"status":"ok","css_bytes":%d,"js_bytes":%d}`, cssLen, len(popupKillerJS))
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

// popupKillerJS is the popup-killer script served from /killer.js.
// Extracted from the old inline injection for reuse.
const popupKillerJS = `(function(){
    window.open=function(){ console.log('[BlockAds] Prevented window.open'); return null;};
    window.alert=function(){};
    window.confirm=function(){return false;};
    window.prompt=function(){return null;};

    var _ce=document.createElement.bind(document);
    document.createElement=function(t){
        var el = _ce(t);
        if(t.toLowerCase() === 'a'){
            el.addEventListener('click', function(e){
                if(el.target === '_blank') { 
                    e.preventDefault(); 
                    console.log('[BlockAds] Prevented hidden link click');
                }
            });
        }
        return el;
    };

    document.addEventListener('click', function(e){
        var el = e.target;
        var style = window.getComputedStyle(el);
        
        if(el.tagName === 'DIV' && style.zIndex > 9000 && (style.position === 'absolute' || style.position === 'fixed')) {
            e.stopPropagation(); 
            e.preventDefault();  
            el.remove();         
        }
    }, true);
})();`

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
