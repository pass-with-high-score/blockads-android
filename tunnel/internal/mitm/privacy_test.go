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
