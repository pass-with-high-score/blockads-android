package tunnel

import (
	"bytes"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"testing"
)

func buildTestClientHello(t *testing.T, serverName string) []byte {
	t.Helper()
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()

	go func() {
		tc := tls.Client(client, &tls.Config{ServerName: serverName, InsecureSkipVerify: true})
		_ = tc.Handshake()
	}()

	buf := make([]byte, 4096)
	n, err := server.Read(buf)
	if err != nil {
		t.Fatalf("read ClientHello: %v", err)
	}
	return buf[:n]
}

func TestSniProxyRuleParsingAndMatching(t *testing.T) {
	e := NewEngine()
	e.SetSniProxyRules(fmt.Sprintf(`[
		{"targetAddr":"91.239.7.227","uids":[10250],"domains":["*.youtubei.googleapis.com","googlevideo.com"],"enabled":true},
		{"targetAddr":"1.2.3.4:8443","uids":[],"domains":["example.com"],"enabled":false}
	]`))

	ptr := e.sniProxyRules.Load()
	if ptr == nil || len(*ptr) != 1 {
		t.Fatalf("expected 1 active rule, got %v", ptr)
	}
	rule := (*ptr)[0]
	if rule.targetAddr != "91.239.7.227:443" {
		t.Fatalf("expected target 91.239.7.227:443, got %s", rule.targetAddr)
	}
	if !rule.uids[10250] {
		t.Fatalf("expected uid 10250 to be indexed")
	}
	if !matchSniDomain("youtubei.googleapis.com", rule.domains) {
		t.Fatalf("expected exact domain match")
	}
	if !matchSniDomain("r1---sn-4g5ednsd.googlevideo.com", rule.domains) {
		t.Fatalf("expected subdomain match")
	}
	if matchSniDomain("accounts.google.com", rule.domains) {
		t.Fatalf("expected accounts.google.com not to match")
	}
}

func TestSniProxyRedirectAndFallback(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	received := make(chan []byte, 1)
	go func() {
		c, aerr := ln.Accept()
		if aerr != nil {
			return
		}
		defer c.Close()
		buf := make([]byte, 4096)
		n, _ := c.Read(buf)
		received <- append([]byte(nil), buf[:n]...)
		_, _ = c.Write([]byte("PROXY_ACK"))
	}()

	e := NewEngine()
	e.SetSniProxyRules(fmt.Sprintf(`[{"targetAddr":%q,"uids":[],"domains":["youtubei.googleapis.com"],"enabled":true}]`, ln.Addr().String()))

	// 1. Matching SNI redirects to target listener
	hello := buildTestClientHello(t, "youtubei.googleapis.com")
	c1, s1 := net.Pipe()
	go func() {
		_, _ = c1.Write(hello)
		_ = c1.Close()
	}()
	handled, nextConn := trySniProxyRedirect(s1, flowID{serverPort: 443}, e, nil, nil)
	_ = s1.Close()
	if !handled || nextConn != nil {
		t.Fatalf("expected handled=true nextConn=nil for matching SNI, got handled=%v nextConn=%v", handled, nextConn)
	}
	got := <-received
	if !bytes.Equal(got, hello) {
		t.Fatalf("target did not receive exact ClientHello bytes")
	}

	// 2. Non-matching SNI returns prefixConn with exact ClientHello intact
	otherHello := buildTestClientHello(t, "accounts.google.com")
	c2, s2 := net.Pipe()
	go func() {
		_, _ = c2.Write(otherHello)
		_ = c2.Close()
	}()
	handled2, nextConn2 := trySniProxyRedirect(s2, flowID{serverPort: 443}, e, nil, nil)
	if handled2 || nextConn2 == nil {
		t.Fatalf("expected fallback prefixConn for non-matching SNI")
	}
	replayed, _ := io.ReadAll(nextConn2)
	_ = nextConn2.Close()
	if !bytes.Equal(replayed, otherHello) {
		t.Fatalf("expected replayed ClientHello to match original")
	}
}
