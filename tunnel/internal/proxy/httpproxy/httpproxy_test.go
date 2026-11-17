package httpproxy

import (
	"bufio"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"testing"
)

func TestFromJSON(t *testing.T) {
	cfg, err := FromJSON(`{"server":"1.2.3.4","port":3128,"username":"u","password":"p","protocol":"https"}`)
	if err != nil {
		t.Fatalf("FromJSON failed: %v", err)
	}
	if !cfg.TLS || cfg.SNI != "1.2.3.4" || cfg.Username != "u" || cfg.Password != "p" {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func TestHTTPConnectWithBasicAuth(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	errCh := make(chan error, 1)
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			errCh <- err
			return
		}
		defer conn.Close()

		br := bufio.NewReader(conn)
		req, err := http.ReadRequest(br)
		if err != nil {
			errCh <- err
			return
		}
		wantAuth := "Basic " + base64.StdEncoding.EncodeToString([]byte("alice:secret"))
		if req.Method != http.MethodConnect || req.Header.Get("Proxy-Authorization") != wantAuth {
			_, _ = conn.Write([]byte("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"))
			errCh <- io.ErrUnexpectedEOF
			return
		}
		// Write response + immediate payload in same write to test bufferedConn draining.
		_, _ = conn.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\nhello"))
		errCh <- nil
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client, err := NewClient(&Config{
		Server:   "127.0.0.1",
		Port:     addr.Port,
		Username: "alice",
		Password: "secret",
	}, nil)
	if err != nil {
		t.Fatal(err)
	}

	c, err := client.DialTCP(net.ParseIP("1.1.1.1"), 443)
	if err != nil {
		t.Fatalf("DialTCP failed: %v", err)
	}
	defer c.Close()

	buf := make([]byte, 5)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatal(err)
	}
	if string(buf) != "hello" {
		t.Fatalf("expected hello, got %q", buf)
	}
	if srvErr := <-errCh; srvErr != nil {
		t.Fatalf("server error: %v", srvErr)
	}
}
