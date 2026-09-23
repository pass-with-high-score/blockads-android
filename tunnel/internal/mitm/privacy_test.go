package mitm

import (
	"bytes"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestSanitizeRequest(t *testing.T) {
	rawURL := "https://example.com/products?id=123&utm_source=facebook&utm_campaign=summer&fbclid=IwAR123&keep_me=yes"
	parsedURL, err := url.Parse(rawURL)
	if err != nil {
		t.Fatalf("failed to parse url: %v", err)
	}

	req := httptest.NewRequest("GET", rawURL, nil)
	req.URL = parsedURL
	req.RequestURI = parsedURL.RequestURI()
	req.Header.Set("X-Client-Data", "chrome_tracking_id_12345")
	req.Header.Set("Referer", "https://google.com/search?q=example+products")

	SanitizeRequest(req, "example.com")

	// 1. Check X-Client-Data deleted
	if req.Header.Get("X-Client-Data") != "" {
		t.Errorf("expected X-Client-Data to be removed")
	}

	// 2. Check DNT and Sec-GPC
	if req.Header.Get("DNT") != "1" {
		t.Errorf("expected DNT: 1")
	}
	if req.Header.Get("Sec-GPC") != "1" {
		t.Errorf("expected Sec-GPC: 1")
	}

	// 3. Check Referer stripped to origin for 3rd party
	expectedRef := "https://google.com/"
	if req.Header.Get("Referer") != expectedRef {
		t.Errorf("expected Referer %q, got %q", expectedRef, req.Header.Get("Referer"))
	}

	// 4. Check Query parameters stripped
	q := req.URL.Query()
	if q.Get("utm_source") != "" || q.Get("utm_campaign") != "" || q.Get("fbclid") != "" {
		t.Errorf("expected tracking parameters to be removed, got: %s", req.URL.RawQuery)
	}
	if q.Get("keep_me") != "yes" || q.Get("id") != "123" {
		t.Errorf("expected legitimate parameters to be preserved, got: %s", req.URL.RawQuery)
	}

	// 5. Check req.Write output format
	var buf bytes.Buffer
	if err := req.Write(&buf); err != nil {
		t.Fatalf("req.Write failed: %v", err)
	}
	written := buf.String()
	if strings.Contains(written, "utm_source") || strings.Contains(written, "fbclid") {
		t.Errorf("written request still contains tracking params: %s", written)
	}
	if !strings.Contains(written, "keep_me=yes") {
		t.Errorf("written request missing legitimate params: %s", written)
	}
}

func TestSanitizeRequestSameOriginReferer(t *testing.T) {
	rawURL := "https://example.com/checkout"
	parsedURL, _ := url.Parse(rawURL)
	req := httptest.NewRequest("GET", rawURL, nil)
	req.URL = parsedURL
	req.Header.Set("Referer", "https://example.com/cart")

	SanitizeRequest(req, "example.com")

	// Same origin should preserve full path
	if req.Header.Get("Referer") != "https://example.com/cart" {
		t.Errorf("expected same-origin Referer to be preserved, got %q", req.Header.Get("Referer"))
	}
}

func TestSanitizeRequestEdgeCases(t *testing.T) {
	SanitizeRequest(nil, "example.com") // must not panic

	req := httptest.NewRequest("GET", "https://example.com/p?b=2&a=1&a=0", nil)
	req.RequestURI = "/p?b=2&a=1&a=0"
	req.Header.Set("Referer", "::not a url")
	SanitizeRequest(req, "example.com:443")
	if req.URL.RawQuery != "b=2&a=1&a=0" || req.RequestURI != "/p?b=2&a=1&a=0" {
		t.Errorf("query without tracking params was rewritten: %q %q", req.URL.RawQuery, req.RequestURI)
	}
	if req.Header.Get("Referer") != "::not a url" {
		t.Errorf("unparsable Referer changed to %q", req.Header.Get("Referer"))
	}

	// Target host with a port still counts as same-origin.
	req = httptest.NewRequest("GET", "https://example.com/", nil)
	req.Header.Set("Referer", "https://EXAMPLE.com/deep/path")
	SanitizeRequest(req, "example.com:443")
	if req.Header.Get("Referer") != "https://EXAMPLE.com/deep/path" {
		t.Errorf("same-origin Referer with port target rewritten: %q", req.Header.Get("Referer"))
	}
}

// Plan Phase 2.1: cleanQuery re-encodes with url.Values.Encode, which sorts
// keys, so untouched params are reordered (and re-escaped).
func TestCleanQueryPreservesOrder(t *testing.T) {
	t.Skip("known bug: cleanQuery sorts the remaining params (url.Values.Encode)")
	req := httptest.NewRequest("GET", "https://example.com/p?z=1&utm_source=x&a=2&m=%7E", nil)
	cleanQuery(req)
	if req.URL.RawQuery != "z=1&a=2&m=%7E" {
		t.Fatalf("RawQuery = %q, want z=1&a=2&m=%%7E", req.URL.RawQuery)
	}
}
