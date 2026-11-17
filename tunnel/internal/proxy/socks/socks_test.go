package socks

import (
	"encoding/binary"
	"io"
	"net"
	"testing"
)

func TestFromJSON(t *testing.T) {
	cfg, err := FromJSON(`{"server":"1.2.3.4","port":1080,"username":"u","password":"p"}`)
	if err != nil {
		t.Fatalf("FromJSON failed: %v", err)
	}
	if cfg.Version != "socks5" || cfg.Username != "u" || cfg.Password != "p" {
		t.Fatalf("unexpected config: %+v", cfg)
	}

	cfg4, err := FromJSON(`{"server":"1.2.3.4","port":1080,"protocol":"socks4","user":"uid"}`)
	if err != nil {
		t.Fatalf("FromJSON socks4 failed: %v", err)
	}
	if cfg4.Version != "socks4" || cfg4.Username != "uid" {
		t.Fatalf("unexpected socks4 config: %+v", cfg4)
	}
}

func TestSOCKS5DialTCPWithAuth(t *testing.T) {
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

		// Greeting
		var greet [4]byte
		if _, err := io.ReadFull(conn, greet[:]); err != nil {
			errCh <- err
			return
		}
		// Select username/password auth (0x02)
		_, _ = conn.Write([]byte{0x05, 0x02})

		// Auth subnegotiation
		var authHdr [2]byte
		if _, err := io.ReadFull(conn, authHdr[:]); err != nil {
			errCh <- err
			return
		}
		u := make([]byte, authHdr[1])
		_, _ = io.ReadFull(conn, u)
		var pLen [1]byte
		_, _ = io.ReadFull(conn, pLen[:])
		p := make([]byte, pLen[0])
		_, _ = io.ReadFull(conn, p)
		if string(u) != "alice" || string(p) != "secret" {
			_, _ = conn.Write([]byte{0x01, 0x01})
			errCh <- io.ErrUnexpectedEOF
			return
		}
		_, _ = conn.Write([]byte{0x01, 0x00})

		// CONNECT request (IPv4)
		var req [10]byte
		if _, err := io.ReadFull(conn, req[:]); err != nil {
			errCh <- err
			return
		}
		port := binary.BigEndian.Uint16(req[8:10])
		if req[1] != 0x01 || req[3] != 0x01 || port != 443 {
			errCh <- io.ErrUnexpectedEOF
			return
		}
		_, _ = conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0})

		// Echo payload
		buf := make([]byte, 4)
		if _, err := io.ReadFull(conn, buf); err != nil {
			errCh <- err
			return
		}
		_, _ = conn.Write(buf)
		errCh <- nil
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client, err := NewClient(&Config{
		Server:   "127.0.0.1",
		Port:     addr.Port,
		Username: "alice",
		Password: "secret",
		Version:  "socks5",
	}, nil)
	if err != nil {
		t.Fatal(err)
	}

	c, err := client.DialTCP(net.ParseIP("1.1.1.1"), 443)
	if err != nil {
		t.Fatalf("DialTCP failed: %v", err)
	}
	defer c.Close()

	if _, err := c.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	resp := make([]byte, 4)
	if _, err := io.ReadFull(c, resp); err != nil {
		t.Fatal(err)
	}
	if string(resp) != "ping" {
		t.Fatalf("expected ping, got %q", resp)
	}
	if srvErr := <-errCh; srvErr != nil {
		t.Fatalf("server error: %v", srvErr)
	}
}

func TestSOCKS4DialTCP(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 32)
		n, _ := conn.Read(buf)
		if n < 9 || buf[0] != 0x04 || buf[1] != 0x01 {
			return
		}
		_, _ = conn.Write([]byte{0x00, 0x5A, 0, 0, 0, 0, 0, 0})
		_, _ = conn.Write([]byte("ok"))
	}()

	addr := ln.Addr().(*net.TCPAddr)
	client, err := NewClient(&Config{
		Server:   "127.0.0.1",
		Port:     addr.Port,
		Username: "uid",
		Version:  "socks4",
	}, nil)
	if err != nil {
		t.Fatal(err)
	}

	c, err := client.DialTCP(net.ParseIP("93.184.216.34"), 80)
	if err != nil {
		t.Fatalf("SOCKS4 DialTCP failed: %v", err)
	}
	defer c.Close()

	got := make([]byte, 2)
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != "ok" {
		t.Fatalf("expected ok, got %q", got)
	}
}
