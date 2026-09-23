package mitm

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestInjectPlainHTML(t *testing.T) {
	rawHTML := "<!DOCTYPE html><html><head><title>Test</title></head><body>Hello</body></html>"
	rawResp := fmt.Sprintf("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: %d\r\n\r\n%s", len(rawHTML), rawHTML)

	resp, err := http.ReadResponse(bufio.NewReader(strings.NewReader(rawResp)), nil)
	if err != nil {
		t.Fatalf("ReadResponse failed: %v", err)
	}

	if !ShouldInjectHTML(resp.Header.Get("Content-Type")) {
		t.Fatalf("ShouldInjectHTML returned false")
	}

	wrapResponseForInjection(resp)

	var out bytes.Buffer
	if err := resp.Write(&out); err != nil {
		t.Fatalf("resp.Write failed: %v", err)
	}

	outStr := out.String()
	if !strings.Contains(outStr, "local.pwhs.app/cosmetic.css") {
		t.Errorf("Injected CSS not found in response:\n%s", outStr)
	}
}

func TestInjectGzipHTML(t *testing.T) {
	rawHTML := "<!DOCTYPE html><html><head><title>Test</title></head><body>Hello</body></html>"
	var gzBuf bytes.Buffer
	gw := gzip.NewWriter(&gzBuf)
	gw.Write([]byte(rawHTML))
	gw.Close()

	resp := &http.Response{
		StatusCode: 200,
		ProtoMajor: 1,
		ProtoMinor: 1,
		Header:     make(http.Header),
		Body:       io.NopCloser(bytes.NewReader(gzBuf.Bytes())),
	}
	resp.Header.Set("Content-Type", "text/html; charset=UTF-8")
	resp.Header.Set("Content-Encoding", "gzip")

	wrapResponseForInjection(resp)

	var out bytes.Buffer
	if err := resp.Write(&out); err != nil {
		t.Fatalf("resp.Write failed: %v", err)
	}

	outStr := out.String()
	if !strings.Contains(outStr, "local.pwhs.app/cosmetic.css") {
		t.Errorf("Injected CSS not found in response:\n%s", outStr)
	}
}

func TestInjectLiveLeeAPK(t *testing.T) {
	req, _ := http.NewRequest("GET", "https://leeapk.com/proton-mail-mod-apk/", nil)
	req.Header.Set("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	tr := &http.Transport{DisableCompression: true}
	resp, err := tr.RoundTrip(req)
	if err != nil {
		t.Skipf("Cannot reach leeapk.com: %v", err)
	}
	defer resp.Body.Close()

	t.Logf("Live Status: %s, Content-Type: %s, Content-Encoding: %s",
		resp.Status, resp.Header.Get("Content-Type"), resp.Header.Get("Content-Encoding"))

	if !ShouldInjectHTML(resp.Header.Get("Content-Type")) {
		t.Fatalf("ShouldInjectHTML returned false for %s", resp.Header.Get("Content-Type"))
	}

	wrapResponseForInjection(resp)

	var out bytes.Buffer
	if err := resp.Write(&out); err != nil {
		t.Fatalf("resp.Write failed: %v", err)
	}

	outStr := out.String()
	if !strings.Contains(outStr, "local.pwhs.app/cosmetic.css") {
		t.Errorf("Injected CSS not found in live response! Head snippet:\n%s", outStr[:min(1000, len(outStr))])
	} else {
		t.Logf("Successfully injected into live LeeAPK response!")
	}
}

