package tunnel

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"compress/zlib"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// ─────────────────────────────────────────────────────────────────────────────
// mitm_common.go — helpers shared between the userspace-stack MITM
// handler (mitm_handler.go) and any future alternative paths.
//
// Originally these lived in mitm_proxy.go alongside the legacy
// CONNECT-based proxy. When the userspace TCP/IP stack became the
// production path (Phase E), the CONNECT proxy was deleted but these
// primitives remained useful and moved here.
// ─────────────────────────────────────────────────────────────────────────────

const (
	dialTimeout     = 5 * time.Second  // Short — fail fast on Android mobile networks.
	idleTimeout     = 30 * time.Second
	maxConnLifetime = 3 * time.Minute
)

// adBlockChecker is the interface the MITM handler uses to query the
// ad-block engine. The Engine implements this (IsDomainBlocked via the
// Trie, lookupIP via the configured resolver).
type adBlockChecker interface {
	IsDomainBlocked(host string) bool
	lookupIP(host string) (net.IP, error)
}

// requestAcceptsHTML returns true when the request's Accept header
// explicitly includes text/html — i.e., the client is requesting an
// HTML document, not a subresource. Used to decide when to strip
// Accept-Encoding so responses arrive uncompressed for injection.
func requestAcceptsHTML(req *http.Request) bool {
	accept := req.Header.Get("Accept")
	return strings.Contains(strings.ToLower(accept), "text/html")
}

// wrapResponseForInjection prepares an HTML response for in-stream
// <link> injection. It transparently decompresses gzip/deflate bodies
// so the injector can find <head in plaintext, strips
// Content-Security-Policy (which would otherwise block the injected
// <link href="https://local.pwhs.app/...">), and clears framing
// headers so Go re-emits the modified body as chunked plaintext. If
// the body uses an encoding we cannot decode (brotli, compress, or
// anything else), the function returns without modifying the response
// and injection is skipped — the page still renders correctly, just
// without cosmetic filtering.
func wrapResponseForInjection(resp *http.Response) {
	encoding := strings.ToLower(strings.TrimSpace(resp.Header.Get("Content-Encoding")))

	var bodyReader io.Reader
	switch encoding {
	case "", "identity":
		bodyReader = resp.Body
	case "gzip":
		gr, err := gzip.NewReader(resp.Body)
		if err != nil {
			return
		}
		bodyReader = gr
	case "deflate":
		// HTTP Content-Encoding: deflate is historically ambiguous: some
		// servers send zlib-wrapped (RFC 1950), others send raw DEFLATE
		// (RFC 1951). Try zlib first (most servers), fall back to raw
		// flate. Buffer the body so we can re-read on fallback.
		raw, err := io.ReadAll(resp.Body)
		if err != nil {
			return
		}
		if zr, err := zlib.NewReader(bytes.NewReader(raw)); err == nil {
			bodyReader = zr
		} else {
			bodyReader = flate.NewReader(bytes.NewReader(raw))
		}
	default:
		return
	}

	resp.Body = io.NopCloser(NewInjectingReader(bodyReader))
	resp.ContentLength = -1
	resp.Header.Del("Content-Length")
	resp.Header.Del("Content-Encoding")
	resp.Header.Del("Transfer-Encoding")
	resp.Header.Del("Content-Security-Policy")
	resp.Header.Del("Content-Security-Policy-Report-Only")
	resp.TransferEncoding = nil
	resp.Uncompressed = true
}

// isLoopbackOrInternal returns true if the hostname is a literal
// loopback or private/internal IP address. Prevents the MITM handler
// from intercepting LAN services (router admin UIs, local printers)
// that typically have self-signed or no TLS certs.
func isLoopbackOrInternal(hostname string) bool {
	lower := strings.ToLower(hostname)
	if lower == "localhost" || lower == "0.0.0.0" || lower == "::" {
		return true
	}

	ip := net.ParseIP(hostname)
	if ip == nil {
		return false
	}

	return ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || isPrivateIP(ip)
}

// isPrivateIP checks if an IP is in RFC 1918 private ranges.
func isPrivateIP(ip net.IP) bool {
	privateRanges := []string{
		"10.0.0.0/8",
		"172.16.0.0/12",
		"192.168.0.0/16",
	}
	for _, cidrStr := range privateRanges {
		_, cidr, _ := net.ParseCIDR(cidrStr)
		if cidr.Contains(ip) {
			return true
		}
	}
	return false
}
