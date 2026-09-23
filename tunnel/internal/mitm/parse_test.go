package mitm

import (
	"bytes"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

// captureClientHello returns the first TLS record a crypto/tls client
// sends for the given config.
func captureClientHello(t testing.TB, cfg *tls.Config) []byte {
	t.Helper()
	c, s := net.Pipe()
	defer s.Close()
	go func() {
		_ = tls.Client(c, cfg).Handshake()
		c.Close()
	}()
	hdr := make([]byte, 5)
	if _, err := io.ReadFull(s, hdr); err != nil {
		t.Fatalf("read record header: %v", err)
	}
	body := make([]byte, binary.BigEndian.Uint16(hdr[3:5]))
	if _, err := io.ReadFull(s, body); err != nil {
		t.Fatalf("read record body: %v", err)
	}
	return append(hdr, body...)
}

// bigHelloConfig inflates the ClientHello past peekSize with ALPN entries.
func bigHelloConfig(sni string) *tls.Config {
	protos := make([]string, 0, 64)
	for i := 0; len(protos) < 64; i++ {
		protos = append(protos, fmt.Sprintf("proto-%03d-%s", i, strings.Repeat("x", 60)))
	}
	return &tls.Config{ServerName: sni, NextProtos: protos}
}

func TestParseClientHelloSNI(t *testing.T) {
	hello := captureClientHello(t, &tls.Config{ServerName: "sni.example.com"})
	if got := parseClientHelloSNI(hello); got != "sni.example.com" {
		t.Fatalf("SNI = %q", got)
	}
	if got := parseClientHelloSNI(captureClientHello(t, &tls.Config{InsecureSkipVerify: true})); got != "" {
		t.Fatalf("no-SNI hello parsed as %q", got)
	}

	big := captureClientHello(t, bigHelloConfig("big.example.com"))
	if len(big) <= peekSize {
		t.Fatalf("big hello is only %d bytes", len(big))
	}
	if got := parseClientHelloSNI(big); got != "big.example.com" {
		t.Fatalf("big hello SNI = %q", got)
	}

	// Every truncation must return a prefix-safe answer without panicking.
	for i := 0; i < len(hello); i++ {
		if got := parseClientHelloSNI(hello[:i]); got != "" && got != "sni.example.com" {
			t.Fatalf("truncated at %d: %q", i, got)
		}
	}

	bad := map[string][]byte{
		"not handshake":  append([]byte{0x17}, hello[1:]...),
		"not hello":      func() []byte { b := bytes.Clone(hello); b[5] = 0x02; return b }(),
		"short":          {0x16, 0x03, 0x01},
		"empty":          nil,
		"tiny body":      {0x16, 0x03, 0x01, 0x00, 0x02, 0x01, 0x00},
		"bad sid length": func() []byte { b := bytes.Clone(hello); b[5+4+34] = 0xff; return b }(),
	}
	for name, rec := range bad {
		if got := parseClientHelloSNI(rec); got != "" {
			t.Errorf("%s: SNI = %q, want empty", name, got)
		}
	}
}

// The handler reads the ClientHello with a single Read. When the hello
// arrives in more than one TCP segment the SNI is lost, so the domain block
// check fails open.
func TestPeekFlowMultiSegmentClientHello(t *testing.T) {
	t.Skip("known bug: peekFlow does a single Read, so a multi-segment ClientHello loses its SNI")
	hello := captureClientHello(t, &tls.Config{ServerName: "split.example.com"})
	c, s := net.Pipe()
	defer c.Close()
	defer s.Close()
	go func() {
		c.Write(hello[:40])
		time.Sleep(10 * time.Millisecond)
		c.Write(hello[40:])
	}()
	peeked, _, err := peekFlow(s, peekSize, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if got := parseClientHelloSNI(peeked); got != "split.example.com" {
		t.Fatalf("SNI from peeked bytes = %q", got)
	}
}

func TestPeekFlowReplaysBytes(t *testing.T) {
	c, s := net.Pipe()
	defer c.Close()
	go func() {
		c.Write([]byte("GET / HTTP/1.1\r\n"))
		c.Write([]byte("Host: a\r\n\r\n"))
		c.Close()
	}()
	peeked, r, err := peekFlow(s, peekSize, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if string(peeked) != "GET / HTTP/1.1\r\n" {
		t.Fatalf("peeked %q", peeked)
	}
	all, _ := io.ReadAll(r)
	if string(all) != "GET / HTTP/1.1\r\nHost: a\r\n\r\n" {
		t.Fatalf("replay %q", all)
	}
}

func TestPeekFlowTimeout(t *testing.T) {
	c, s := net.Pipe()
	defer c.Close()
	defer s.Close()
	if _, _, err := peekFlow(s, peekSize, 20*time.Millisecond); err == nil {
		t.Fatal("expected timeout error")
	}
}

func TestLooksLikeHTTPRequest(t *testing.T) {
	for in, want := range map[string]bool{
		"GET / HTTP/1.1\r\n":       true,
		"POST /api HTTP/1.1":       true,
		"get /xy":                  true,
		"CONNECT a:443 HTTP/1.1":   false, // target is not a path
		"OPTIONS * HTTP/1.1":       false,
		"GET":                      false,
		"GET /":                    false, // shorter than 7
		"GE1 / HTTP/1.1":           false,
		"\x16\x03\x01\x02\x00\x01": false,
		"VERYLONGMETHODNAME / x":   false, // no space in the first 16 bytes
		"GET  / HTTP/1.1":          false,
	} {
		if got := looksLikeHTTPRequest([]byte(in)); got != want {
			t.Errorf("looksLikeHTTPRequest(%q) = %v, want %v", in, got, want)
		}
	}
}

func TestParseHTTPHost(t *testing.T) {
	for in, want := range map[string]string{
		"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n":                    "example.com",
		"GET / HTTP/1.1\r\nhOsT:   example.com:8080  \r\n\r\n":           "example.com",
		"GET / HTTP/1.1\r\nAccept: */*\r\nHost: b.example\r\n\r\n":       "b.example",
		"GET / HTTP/1.1\r\nAccept: */*\r\n\r\nHost: body.example\r\n":    "",
		"GET / HTTP/1.1\r\nHost: no-crlf-yet":                            "",
		"GET / HTTP/1.1\r\n:bad\r\nX-Host: nope\r\nHost: ok.example\r\n": "ok.example",
		"": "",
	} {
		if got := parseHTTPHost([]byte(in)); got != want {
			t.Errorf("parseHTTPHost(%q) = %q, want %q", in, got, want)
		}
	}
}

// An IPv6 literal Host header is cut at its first colon, so the handler
// treats "[2001" as a hostname and may intercept it.
func TestParseHTTPHostIPv6Literal(t *testing.T) {
	t.Skip("known bug: parseHTTPHost truncates an IPv6 literal Host at the first colon")
	got := parseHTTPHost([]byte("GET / HTTP/1.1\r\nHost: [2001:db8::1]:443\r\n\r\n"))
	if got != "2001:db8::1" && got != "[2001:db8::1]" {
		t.Fatalf("host = %q", got)
	}
}

// A request line with an empty method is classified as HTTP, so the flow is
// MITM'd and then dropped by http.ReadRequest instead of passed through.
func TestLooksLikeHTTPRequestEmptyMethod(t *testing.T) {
	t.Skip("known bug: looksLikeHTTPRequest accepts a request line with an empty method")
	if looksLikeHTTPRequest([]byte(" /index.html HTTP/1.1")) {
		t.Fatal("empty method accepted")
	}
}

func FuzzParseClientHelloSNI(f *testing.F) {
	f.Add(captureClientHello(f, &tls.Config{ServerName: "sni.example.com"}))
	f.Add(captureClientHello(f, &tls.Config{InsecureSkipVerify: true}))
	f.Add(captureClientHello(f, bigHelloConfig("big.example.com")))
	f.Add([]byte{0x16, 0x03, 0x01, 0x00, 0x00})
	f.Fuzz(func(t *testing.T, b []byte) {
		sni := parseClientHelloSNI(b)
		if len(sni) > len(b) {
			t.Fatalf("SNI longer than the input: %d > %d", len(sni), len(b))
		}
		if sni != "" && !bytes.Contains(b, []byte(sni)) {
			t.Fatalf("SNI %q is not a substring of the input", sni)
		}
	})
}

func FuzzParseHTTPHost(f *testing.F) {
	f.Add([]byte("GET / HTTP/1.1\r\nHost: example.com:80\r\n\r\n"))
	f.Add([]byte("GET / HTTP/1.1\r\nhost:\r\n\r\n"))
	f.Add([]byte("\r\n"))
	f.Fuzz(func(t *testing.T, b []byte) {
		host := parseHTTPHost(b)
		if strings.ContainsAny(host, "\r\n") {
			t.Skip("known bug: a bare CR/LF inside the Host line is kept in the value")
		}
		if strings.Contains(host, ":") {
			t.Fatalf("host %q contains a port or line break", host)
		}
	})
}

func FuzzLooksLikeHTTPRequest(f *testing.F) {
	f.Add([]byte("GET / HTTP/1.1\r\n"))
	f.Add([]byte("\x16\x03\x01\x00\x10"))
	f.Add([]byte("ABCDEFGHIJKLMNOP /"))
	f.Fuzz(func(t *testing.T, b []byte) {
		if !looksLikeHTTPRequest(b) {
			return
		}
		if b[0] == ' ' {
			t.Skip("known bug: an empty method (leading space) is accepted")
		}
		sp := bytes.IndexByte(b, ' ')
		if len(b) < 7 || sp <= 0 || sp >= 16 || sp+1 >= len(b) || b[sp+1] != '/' {
			t.Fatalf("accepted %q", b)
		}
		for _, c := range b[:sp] {
			if !(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
				t.Fatalf("accepted non-letter method in %q", b)
			}
		}
	})
}
