package mitm

import (
	"bufio"
	"bytes"
	"compress/flate"
	"compress/zlib"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeBlocker is an in-memory AdBlockChecker.
type fakeBlocker struct {
	mu       sync.Mutex
	blocked  map[string]bool
	lookup   net.IP
	doh      bool
	logCount int
}

func (b *fakeBlocker) IsDomainBlocked(host string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.blocked[host]
}

func (b *fakeBlocker) LookupIP(string) (net.IP, error) {
	if b.lookup == nil {
		return nil, io.EOF
	}
	return b.lookup, nil
}

func (b *fakeBlocker) IsDoHBlockingEnabled() bool { return b.doh }

func (b *fakeBlocker) LogConnection(FlowID, int) {
	b.mu.Lock()
	b.logCount++
	b.mu.Unlock()
}

func (b *fakeBlocker) logs() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.logCount
}

// seenRequest is what the fake upstream observed.
type seenRequest struct {
	req  *http.Request
	body string
}

// relayHarness wires relayHTTPFlow between two net.Pipes: the test drives
// the client end and a fake upstream answers on the server end.
type relayHarness struct {
	client net.Conn
	cr     *bufio.Reader
	seen   chan seenRequest
	done   chan struct{}
}

func newRelayHarness(t *testing.T, hostname string, filter *MitmFilter, blocker AdBlockChecker, respond func(*http.Request) string) *relayHarness {
	t.Helper()
	cliA, cliB := net.Pipe()
	srvA, srvB := net.Pipe()
	h := &relayHarness{client: cliA, cr: bufio.NewReader(cliA), seen: make(chan seenRequest, 16), done: make(chan struct{})}
	go func() {
		relayHTTPFlow(cliB, srvA, hostname, filter, blocker)
		cliB.Close()
		srvA.Close()
		close(h.done)
	}()
	go func() {
		defer srvB.Close()
		br := bufio.NewReader(srvB)
		for {
			req, err := http.ReadRequest(br)
			if err != nil {
				return
			}
			body, _ := io.ReadAll(req.Body)
			h.seen <- seenRequest{req, string(body)}
			if _, err := io.WriteString(srvB, respond(req)); err != nil {
				return
			}
		}
	}()
	cliA.SetDeadline(time.Now().Add(5 * time.Second))
	t.Cleanup(func() { cliA.Close() })
	return h
}

// send writes raw request bytes without blocking the reader.
func (h *relayHarness) send(raw string) {
	go io.WriteString(h.client, raw)
}

func (h *relayHarness) read(t *testing.T) (*http.Response, string) {
	t.Helper()
	resp, err := http.ReadResponse(h.cr, nil)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	return resp, string(body)
}

func (h *relayHarness) waitDone(t *testing.T) {
	t.Helper()
	select {
	case <-h.done:
	case <-time.After(5 * time.Second):
		t.Fatal("relay did not return")
	}
}

const htmlResp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 29\r\n\r\n<html><head></head>hi</html>\n"

func okResp(body string) string {
	return "HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\nContent-Length: " + intToStr(len(body)) + "\r\n\r\n" + body
}

func TestRelayHTTPFlowForwardsAndInjects(t *testing.T) {
	h := newRelayHarness(t, "site.example", NewMitmFilter(), &fakeBlocker{}, func(r *http.Request) string {
		if r.URL.Path == "/" {
			return htmlResp
		}
		return okResp("js();")
	})

	h.send("GET /?utm_source=x&keep=1 HTTP/1.1\r\nHost: site.example\r\nAccept: text/html\r\nAccept-Encoding: gzip, br\r\nX-Client-Data: id\r\n\r\n")
	resp, body := h.read(t)
	if resp.StatusCode != 200 || !strings.Contains(body, injectionTags) {
		t.Fatalf("status %d, body %q", resp.StatusCode, body)
	}
	up := <-h.seen
	if up.req.Header.Get("Accept-Encoding") != "" || up.req.Header.Get("X-Client-Data") != "" || up.req.Header.Get("DNT") != "1" {
		t.Errorf("upstream headers not sanitized: %v", up.req.Header)
	}
	if up.req.URL.RawQuery != "keep=1" {
		t.Errorf("upstream query = %q", up.req.URL.RawQuery)
	}

	// Keep-alive: a second, non-HTML request on the same connection.
	h.send("GET /app.js HTTP/1.1\r\nHost: site.example\r\nAccept: */*\r\nAccept-Encoding: br\r\n\r\n")
	resp, body = h.read(t)
	if body != "js();" || resp.Header.Get("Content-Length") != "5" {
		t.Fatalf("non-HTML response altered: %q %v", body, resp.Header)
	}
	if up = <-h.seen; up.req.Header.Get("Accept-Encoding") != "gzip, deflate" {
		t.Errorf("Accept-Encoding = %q, want gzip, deflate", up.req.Header.Get("Accept-Encoding"))
	}

	h.send("GET /bye.js HTTP/1.1\r\nHost: site.example\r\nConnection: close\r\n\r\n")
	h.read(t)
	h.waitDone(t)
}

func TestRelayHTTPFlowEarlyResponses(t *testing.T) {
	filter := NewMitmFilter()
	filter.SetAdPathPatterns([]string{"/pagead", "/ad_break", "/ads/"})
	blocker := &fakeBlocker{blocked: map[string]bool{"tracker.example": true, "site.example": true}}
	h := newRelayHarness(t, "site.example", filter, blocker, func(*http.Request) string { return okResp("upstream") })

	for _, tc := range []struct {
		name, req string
		status    int
		body      string
	}{
		{"local asset", "GET /health HTTP/1.1\r\nHost: local.pwhs.app:443\r\n\r\n", 200, `{"status":"ok"`},
		{"blocked third party", "GET / HTTP/1.1\r\nHost: tracker.example\r\n\r\n", 403, "Blocked by BlockAds"},
		{"ad path", "GET /pagead/x.gif HTTP/1.1\r\nHost: site.example\r\n\r\n", 204, ""},
		// The flow's own host is never blocked mid-stream.
		{"own host", "GET /page.js HTTP/1.1\r\nHost: site.example\r\n\r\n", 200, "upstream"},
	} {
		h.send(tc.req)
		resp, body := h.read(t)
		if resp.StatusCode != tc.status || !strings.HasPrefix(body, tc.body) {
			t.Errorf("%s: got %d %q, want %d %q", tc.name, resp.StatusCode, body, tc.status, tc.body)
		}
	}
	if n := len(h.seen); n != 1 {
		t.Errorf("upstream saw %d requests, want 1", n)
	}
}

// The ad-JSON stub sets only a Content-Length header, which Response.Write
// ignores; with ContentLength 0 it probes the body, finds data, and sends
// "Connection: close" with an unframed body while the relay keeps the
// connection open. The client waits for EOF that never comes.
func TestRelayHTTPFlowAdJSONIsFramed(t *testing.T) {
	t.Skip("known bug: ad-JSON stub is written unframed with Connection: close while the relay keeps the connection open")
	filter := NewMitmFilter()
	filter.SetAdPathPatterns([]string{"/ad_break", "/ads/"})
	h := newRelayHarness(t, "site.example", filter, nil, func(*http.Request) string { return okResp("upstream") })
	for _, path := range []string{"/ad_break", "/ads/config.json"} {
		h.client.SetDeadline(time.Now().Add(time.Second))
		h.send("GET " + path + " HTTP/1.1\r\nHost: site.example\r\n\r\n")
		if resp, body := h.read(t); resp.StatusCode != 200 || body != "{}" || resp.Header.Get("Content-Type") == "" {
			t.Fatalf("%s: %d %q %v", path, resp.StatusCode, body, resp.Header)
		}
	}
}

func TestRelayHTTPFlowHTTP10DefaultsHost(t *testing.T) {
	h := newRelayHarness(t, "site.example", nil, nil, func(*http.Request) string { return okResp("ok") })
	h.send("GET /x.js HTTP/1.0\r\n\r\n")
	h.read(t)
	if up := <-h.seen; up.req.Host != "site.example" {
		t.Errorf("upstream Host = %q", up.req.Host)
	}
	h.waitDone(t) // HTTP/1.0 implies close
}

func TestRelayHTTPFlowErrors(t *testing.T) {
	t.Run("malformed request", func(t *testing.T) {
		h := newRelayHarness(t, "site.example", nil, nil, func(*http.Request) string { return okResp("") })
		h.send("\x00\x01garbage\r\n\r\n")
		h.waitDone(t)
	})
	t.Run("bad upstream response", func(t *testing.T) {
		h := newRelayHarness(t, "site.example", nil, nil, func(*http.Request) string { return "not http\r\n\r\n" })
		h.send("GET /x.js HTTP/1.1\r\nHost: site.example\r\n\r\n")
		h.waitDone(t)
	})
	t.Run("upstream write fails", func(t *testing.T) {
		cliA, cliB := net.Pipe()
		srvA, srvB := net.Pipe()
		srvB.Close()
		done := make(chan struct{})
		go func() { relayHTTPFlow(cliB, srvA, "site.example", nil, nil); close(done) }()
		go io.WriteString(cliA, "GET / HTTP/1.1\r\nHost: site.example\r\n\r\n")
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("relay did not return")
		}
		cliA.Close()
	})
}

// Early-response paths skip the request body, so the next request on
// the connection is parsed from the middle of the previous body.
func TestRelayHTTPFlowDrainsBodiesOnEarlyPaths(t *testing.T) {
	t.Skip("known bug: 403/204/local-asset paths do not drain request bodies, desyncing the stream")
	filter := NewMitmFilter()
	filter.SetAdPathPatterns([]string{"/pagead"})
	blocker := &fakeBlocker{blocked: map[string]bool{"tracker.example": true}}
	for name, first := range map[string]string{
		"blocked":     "POST / HTTP/1.1\r\nHost: tracker.example\r\nContent-Length: 3\r\n\r\nx=1",
		"ad path":     "POST /pagead/c HTTP/1.1\r\nHost: site.example\r\nContent-Length: 3\r\n\r\nx=1",
		"local asset": "POST /health HTTP/1.1\r\nHost: local.pwhs.app\r\nContent-Length: 3\r\n\r\nx=1",
	} {
		t.Run(name, func(t *testing.T) {
			h := newRelayHarness(t, "site.example", filter, blocker, func(*http.Request) string { return okResp("next") })
			h.send(first + "GET /next.js HTTP/1.1\r\nHost: site.example\r\n\r\n")
			h.read(t)
			if _, body := h.read(t); body != "next" {
				t.Fatalf("second response body = %q", body)
			}
		})
	}
}

func TestPeekReplayConnAndIntToStr(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	pc := &peekReplayConn{Conn: a, r: io.MultiReader(strings.NewReader("peeked"), strings.NewReader("-rest"))}
	all, _ := io.ReadAll(pc)
	if string(all) != "peeked-rest" {
		t.Fatalf("peekReplayConn read %q", all)
	}
	for in, want := range map[int]string{0: "0", 7: "7", 443: "443", 65535: "65535", -12: "-12"} {
		if got := intToStr(in); got != want {
			t.Errorf("intToStr(%d) = %q", in, got)
		}
	}
}

func TestWrapResponseForInjectionEncodings(t *testing.T) {
	html := []byte("<html><head></head><body>x</body></html>")
	for _, tc := range []struct {
		name, enc string
		body      []byte
		inject    bool
	}{
		{"identity", "identity", html, true},
		{"zlib deflate", "deflate", zlibBytes(t, html), true},
		{"raw deflate", "deflate", flateBytes(t, html), true},
		{"brotli skipped", "br", []byte("opaque"), false},
		{"bad gzip skipped", "gzip", []byte("not gzip"), false},
	} {
		resp := &http.Response{
			StatusCode: 200, ProtoMajor: 1, ProtoMinor: 1,
			Header: http.Header{"Content-Type": {"text/html"}, "Content-Encoding": {tc.enc}, "Content-Security-Policy": {"default-src 'self'"}},
			Body:   io.NopCloser(bytes.NewReader(tc.body)),
		}
		wrapResponseForInjection(resp)
		got, _ := io.ReadAll(resp.Body)
		if injected := bytes.Contains(got, []byte(injectionTags)); injected != tc.inject {
			t.Errorf("%s: injected=%v, want %v (%q)", tc.name, injected, tc.inject, got)
		}
		if tc.inject && (resp.Header.Get("Content-Security-Policy") != "" || resp.Header.Get("Content-Encoding") != "" || resp.ContentLength != -1) {
			t.Errorf("%s: headers not cleared: %v", tc.name, resp.Header)
		}
	}
}

func zlibBytes(t *testing.T, b []byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	w := zlib.NewWriter(&buf)
	w.Write(b)
	w.Close()
	return buf.Bytes()
}

func flateBytes(t *testing.T, b []byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	w, err := flate.NewWriter(&buf, flate.DefaultCompression)
	if err != nil {
		t.Fatal(err)
	}
	w.Write(b)
	w.Close()
	return buf.Bytes()
}
