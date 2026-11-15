package dns

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strconv"
	"sync/atomic"
	"testing"

	miekgdns "github.com/miekg/dns"
)

func startTCPDNS(t *testing.T, respond func(q []byte) []byte) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				var n uint16
				if binary.Read(c, binary.BigEndian, &n) != nil {
					return
				}
				q := make([]byte, n)
				if _, err := io.ReadFull(c, q); err != nil {
					return
				}
				_, _ = c.Write(respond(q))
			}(c)
		}
	}()
	return l.Addr().String()
}

func TestPlainUpgradesToTCPViaProxyDialer(t *testing.T) {
	tcpAddr := startTCPDNS(t, dotAnswer("192.0.2.55"))

	var protected atomic.Int64
	var proxyCalls atomic.Int64
	r := NewResolver(func(int) bool { protected.Add(1); return true })
	defer r.Shutdown()
	r.Configure(ProtocolPlain, "8.8.8.8", "8.8.8.8", "")
	r.SetProxyDialer(func(targetIP net.IP, targetPort int) (net.Conn, bool, error) {
		proxyCalls.Add(1)
		if !targetIP.Equal(net.IPv4(8, 8, 8, 8)) || targetPort != 53 {
			t.Errorf("unexpected target %v:%d", targetIP, targetPort)
		}
		conn, err := net.Dial("tcp", tcpAddr)
		return conn, true, err
	})

	resp, err := r.Resolve(query(t, "example.com", miekgdns.TypeA))
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if ip := firstA(t, resp); ip != "192.0.2.55" {
		t.Errorf("answer = %s, want 192.0.2.55", ip)
	}
	if proxyCalls.Load() != 1 {
		t.Errorf("proxyCalls = %d, want 1", proxyCalls.Load())
	}
	if protected.Load() != 0 {
		t.Errorf("direct UDP protectFn called %d times, want 0", protected.Load())
	}
}

func TestPlainFallsBackToDirectUDPWhenProxyUnused(t *testing.T) {
	srv := startUDP(t, answerA("192.0.2.56"))

	var protected atomic.Int64
	var proxyCalls atomic.Int64
	r := NewResolver(func(int) bool { protected.Add(1); return true })
	defer r.Shutdown()
	r.Configure(ProtocolPlain, srv.addr, srv.addr, "")
	r.SetProxyDialer(func(net.IP, int) (net.Conn, bool, error) {
		proxyCalls.Add(1)
		return nil, false, nil
	})

	resp, err := r.Resolve(query(t, "example.com", miekgdns.TypeA))
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if ip := firstA(t, resp); ip != "192.0.2.56" {
		t.Errorf("answer = %s, want 192.0.2.56", ip)
	}
	if proxyCalls.Load() != 1 {
		t.Errorf("proxyCalls = %d, want 1", proxyCalls.Load())
	}
	if protected.Load() == 0 {
		t.Error("expected direct UDP fallback to call protectFn")
	}
}

func TestDoTViaProxyDialer(t *testing.T) {
	dotAddr := startDoT(t, testCert, dotAnswer("192.0.2.86"))

	var protected atomic.Int64
	var proxyCalls atomic.Int64
	r := NewResolver(func(int) bool { protected.Add(1); return true })
	defer r.Shutdown()
	r.Configure(ProtocolDoT, "127.0.0.1:853", "127.0.0.1:853", "")
	r.SetProxyDialer(func(targetIP net.IP, targetPort int) (net.Conn, bool, error) {
		proxyCalls.Add(1)
		if !targetIP.Equal(net.IPv4(127, 0, 0, 1)) || targetPort != 853 {
			t.Errorf("unexpected DoT target %v:%d", targetIP, targetPort)
		}
		conn, err := net.Dial("tcp", dotAddr)
		return conn, true, err
	})

	resp, err := r.Resolve(query(t, "example.com", miekgdns.TypeA))
	if trustedRoot {
		if err != nil {
			t.Fatalf("Resolve: %v", err)
		}
		if ip := firstA(t, resp); ip != "192.0.2.86" {
			t.Errorf("answer = %s, want 192.0.2.86", ip)
		}
	}
	if proxyCalls.Load() != 1 {
		t.Errorf("proxyCalls = %d, want 1", proxyCalls.Load())
	}
	if protected.Load() != 0 {
		t.Errorf("direct DoT protectFn called %d times, want 0", protected.Load())
	}
}

func TestDoHViaProxyDialer(t *testing.T) {
	for _, http2 := range []bool{false, true} {
		name := "http1"
		if http2 {
			name = "http2"
		}
		t.Run(name, func(t *testing.T) {
			srv := startDoH(t, http2, dohAnswer("192.0.2.87"))
			_, portStr, err := net.SplitHostPort(srv.Listener.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			wantPort, _ := strconv.Atoi(portStr)

			var protected atomic.Int64
			var proxyCalls atomic.Int64
			r := NewResolver(func(int) bool { protected.Add(1); return true })
			defer r.Shutdown()
			r.Configure(ProtocolDoH, noFallback, noFallback, srv.URL+"/dns-query")
			r.SetProxyDialer(func(targetIP net.IP, targetPort int) (net.Conn, bool, error) {
				proxyCalls.Add(1)
				if !targetIP.Equal(net.IPv4(127, 0, 0, 1)) || targetPort != wantPort {
					t.Errorf("unexpected DoH target %v:%d", targetIP, targetPort)
				}
				conn, err := net.Dial("tcp", srv.Listener.Addr().String())
				return conn, true, err
			})

			resp, err := r.Resolve(query(t, "example.com", miekgdns.TypeA))
			if trustedRoot {
				if err != nil {
					t.Fatalf("Resolve: %v", err)
				}
				if ip := firstA(t, resp); ip != "192.0.2.87" {
					t.Errorf("answer = %s, want 192.0.2.87", ip)
				}
			}
			if proxyCalls.Load() != 1 {
				t.Errorf("proxyCalls = %d, want 1", proxyCalls.Load())
			}
			if protected.Load() != 0 {
				t.Errorf("direct DoH protectFn called %d times, want 0", protected.Load())
			}
		})
	}
}

func TestProxyDialerErrorDoesNotFallbackToDirect(t *testing.T) {
	var protected atomic.Int64
	r := NewResolver(func(int) bool { protected.Add(1); return true })
	defer r.Shutdown()
	r.SetProxyDialer(func(net.IP, int) (net.Conn, bool, error) {
		return nil, true, errors.New("proxy dial failed")
	})

	raw := query(t, "example.com", miekgdns.TypeA)

	if _, err := r.queryPlain(raw, "8.8.8.8:53"); err == nil {
		t.Error("queryPlain succeeded despite proxy error")
	}
	if _, err := r.queryDoT(raw, "8.8.8.8:853"); err == nil {
		t.Error("queryDoT succeeded despite proxy error")
	}
	if _, err := r.queryDoH(raw, "https://dns.google/dns-query"); err == nil {
		t.Error("queryDoH succeeded despite proxy error")
	}
	if protected.Load() != 0 {
		t.Errorf("direct protectFn called %d times, want 0", protected.Load())
	}
}
