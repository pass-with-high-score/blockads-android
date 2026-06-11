package tunnel

import (
	"bytes"
	"io"
	"strings"
	"sync"
)

// ─────────────────────────────────────────────────────────────────────────────
// MITM HTML Injector — Streaming modification of HTML responses.
//
// Scans for <head> (case-insensitive) in the response stream and injects
// lightweight <link> and <script> tags pointing to the local asset server
// (local.pwhs.app). The actual CSS/JS is served from memory by the
// local asset server — see mitm_local_server.go.
//
// This replaces the old approach of injecting raw CSS/JS inline (~50-100KB)
// with just two small tags (~120 bytes), dramatically reducing HTML bloat.
// ─────────────────────────────────────────────────────────────────────────────

// injectionTags are the lightweight tags injected after <head>.
// The browser fetches these via HTTPS through the MITM proxy, which
// intercepts "local.pwhs.app" and serves assets from memory.
//
// Three injections, in order:
//  1. <link>   cosmetic.css     — element-hiding rules
//  2. <script> scriptlets.js    — scriptlet runtime (set-constant,
//                                  abort-on-prop, prevent-fetch, …)
//  3. inline   per-host loader  — fetches /sl-<host>.js for the
//     current document.location.host and evals it. Cheaper than
//     parsing the host server-side: the runtime fetch happens once
//     per host, browser caches the response.
const injectionTags = `<link rel="stylesheet" href="https://local.pwhs.app/cosmetic.css">` +
	`<script src="https://local.pwhs.app/scriptlets.js" async></script>` +
	`<script>(function(){var s=document.createElement('script');s.async=true;` +
	`s.src='https://local.pwhs.app/sl-'+encodeURIComponent(location.hostname)+'.js';` +
	`(document.head||document.documentElement).appendChild(s);})();</script>`

// headTagBytes is the pattern to search for (case-insensitive matching done manually).
var headTagBytes = []byte("<head") // matches both <head> and <head ...attributes>

// scanLimit is the maximum number of bytes to scan for <head>.
// If <head> isn't found within this limit, it's likely not a standard HTML page.
const scanLimit = 16 * 1024 // 16KB

// cosmeticCSS holds the cosmetic filter CSS rules (thread-safe).
// Read by mitm_local_server.go when serving /cosmetic.css.
var (
	cosmeticMu  sync.RWMutex
	cosmeticCSS string
)

// SetCosmeticCSS sets the cosmetic filter CSS that will be served by the
// local asset server at https://local.pwhs.app/cosmetic.css.
// Called from Kotlin after parsing EasyList cosmetic rules.
func SetCosmeticCSS(css string) {
	cosmeticMu.Lock()
	cosmeticCSS = css
	cosmeticMu.Unlock()
	logf("Cosmetic CSS updated: %d bytes", len(css))
}

// ShouldInjectHTML checks if a Content-Type header indicates HTML content.
func ShouldInjectHTML(contentType string) bool {
	ct := strings.ToLower(contentType)
	return strings.Contains(ct, "text/html")
}

// injectingReader wraps an io.Reader and injects lightweight asset tags
// after the first <head> or <head ...> tag found in the stream.
//
// It handles edge cases:
//   - <head> split across read boundaries (carry buffer)
//   - Scan limit to avoid scanning large non-HTML bodies
//   - Case-insensitive matching
type injectingReader struct {
	upstream    io.Reader
	injected    bool   // true after injection is done (or scan limit reached)
	pending     []byte // buffered data waiting to be read by the caller
	carry       []byte // bytes carried from end of previous read (potential partial <head match)
	scannedBytes int   // total bytes scanned so far
}

// NewInjectingReader wraps an upstream reader to inject the local asset server
// tags after the first <head> tag.
func NewInjectingReader(upstream io.Reader) io.Reader {
	return &injectingReader{
		upstream: upstream,
	}
}

func (r *injectingReader) Read(p []byte) (int, error) {
	// If we have pending data from a previous injection, drain it first
	if len(r.pending) > 0 {
		n := copy(p, r.pending)
		r.pending = r.pending[n:]
		return n, nil
	}

	// Read from upstream
	n, err := r.upstream.Read(p)
	if n == 0 || r.injected {
		return n, err
	}

	// Prepend any carry bytes from the previous read
	var data []byte
	if len(r.carry) > 0 {
		data = make([]byte, len(r.carry)+n)
		copy(data, r.carry)
		copy(data[len(r.carry):], p[:n])
		r.carry = nil
	} else {
		data = make([]byte, n)
		copy(data, p[:n])
	}

	// Check scan limit — stop trying to inject after 16KB
	r.scannedBytes += len(data)
	if r.scannedBytes > scanLimit {
		r.injected = true
		nn := copy(p, data)
		if nn < len(data) {
			r.pending = data[nn:]
		}
		if err == io.EOF && len(r.pending) > 0 {
			return nn, nil
		}
		return nn, err
	}

	// Search for <head in the data (case-insensitive)
	lower := bytes.ToLower(data)
	idx := bytes.Index(lower, headTagBytes) // finds "<head"

	if idx < 0 {
		// No match found. However, the end of data might contain a partial
		// match (e.g., "<he" could be the start of "<head>").
		// Carry the last len(headTagBytes)-1 bytes to the next read.
		carryLen := len(headTagBytes) - 1
		if carryLen > len(data) {
			carryLen = len(data)
		}
		// Only carry if there's a potential partial match at the end
		endBytes := bytes.ToLower(data[len(data)-carryLen:])
		hasPartial := false
		for i := 1; i <= carryLen; i++ {
			if bytes.Equal(endBytes[carryLen-i:], headTagBytes[:i]) {
				r.carry = make([]byte, i)
				copy(r.carry, data[len(data)-i:])
				hasPartial = true
				// Return everything except the carried bytes
				outData := data[:len(data)-i]
				if len(outData) == 0 {
					// All data is potential carry — need more from upstream
					return 0, err
				}
				nn := copy(p, outData)
				if nn < len(outData) {
					r.pending = outData[nn:]
				}
				if err == io.EOF && len(r.pending) > 0 {
					return nn, nil
				}
				return nn, err
			}
		}
		if !hasPartial {
			nn := copy(p, data)
			if nn < len(data) {
				r.pending = data[nn:]
			}
			if err == io.EOF && len(r.pending) > 0 {
				return nn, nil
			}
			return nn, err
		}
		// unreachable, but just in case
		nn := copy(p, data)
		return nn, err
	}

	// Found "<head" — now find the closing '>' of the head tag
	closeIdx := bytes.IndexByte(lower[idx:], '>')
	if closeIdx < 0 {
		// The '>' hasn't arrived yet — carry everything from idx onward
		r.carry = make([]byte, len(data)-idx)
		copy(r.carry, data[idx:])
		outData := data[:idx]
		if len(outData) == 0 {
			return 0, err
		}
		nn := copy(p, outData)
		if nn < len(outData) {
			r.pending = outData[nn:]
		}
		if err == io.EOF && len(r.pending) > 0 {
			return nn, nil
		}
		return nn, err
	}

	tagEnd := idx + closeIdx + 1
	return r.doInject(p, data, tagEnd, err)
}

// doInject splices the lightweight asset tags into the data buffer right at tagEnd.
func (r *injectingReader) doInject(p []byte, data []byte, tagEnd int, upstreamErr error) (int, error) {
	r.injected = true

	script := []byte(injectionTags)

	// Build: [before + tag] + [injected tags] + [rest of data]
	before := data[:tagEnd]
	after := data[tagEnd:]

	total := len(before) + len(script) + len(after)

	// Try to fit everything into p
	if total <= len(p) {
		// Everything fits in the caller's buffer
		n := copy(p, before)
		n += copy(p[n:], script)
		n += copy(p[n:], after)
		return n, upstreamErr
	}

	// Doesn't fit — copy what we can into p and save the rest in pending
	var combined []byte
	combined = append(combined, before...)
	combined = append(combined, script...)
	combined = append(combined, after...)

	n := copy(p, combined)
	r.pending = combined[n:]

	// Don't propagate EOF yet if we have pending data
	if upstreamErr == io.EOF && len(r.pending) > 0 {
		return n, nil
	}
	return n, upstreamErr
}
