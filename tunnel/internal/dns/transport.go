package dns

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/apernet/quic-go"
)

// queryDoH sends a DNS query via DNS-over-HTTPS (RFC 8484 POST).
func (r *Resolver) queryDoH(rawQuery []byte, dohURL string) ([]byte, error) {
	if dohURL == "" {
		return nil, fmt.Errorf("DoH URL not configured")
	}

	var resp *http.Response
	var err error

	// Retry loop for HTTP/2 unexpected EOF (common with DoH load balancers)
	for attempt := 1; attempt <= 2; attempt++ {
		req, reqErr := http.NewRequest("POST", dohURL, bytes.NewReader(rawQuery))
		if reqErr != nil {
			return nil, fmt.Errorf("DoH request: %w", reqErr)
		}
		req.Header.Set("Content-Type", "application/dns-message")
		req.Header.Set("Accept", "application/dns-message")

		ctx, cancel := context.WithTimeout(context.Background(), queryTimeoutDoH)
		req = req.WithContext(ctx)

		resp, err = r.httpClient.Do(req)
		if err == nil {
			defer cancel()
			break
		}

		cancel()
		errStr := err.Error()
		if strings.Contains(errStr, "EOF") && attempt == 1 {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		return nil, fmt.Errorf("DoH request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == 403 {
		return nil, fmt.Errorf("DoH rate limited (403)")
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("DoH status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return nil, fmt.Errorf("DoH read: %w", err)
	}

	return body, nil
}

// queryDoT sends a DNS query via DNS-over-TLS (RFC 7858).
func (r *Resolver) queryDoT(rawQuery []byte, server string) ([]byte, error) {
	host := server
	port := "853"
	if h, p, err := net.SplitHostPort(server); err == nil {
		host = h
		port = p
	}

	dialer := &net.Dialer{
		Timeout: connectTimeout,
		Control: func(network, address string, c syscall.RawConn) error {
			if r.protectSocketFn != nil {
				return c.Control(func(fd uintptr) {
					r.protectSocketFn(int(fd))
				})
			}
			return nil
		},
	}

	targetIP := host
	if net.ParseIP(host) == nil {
		targetIP = resolveBootstrapIP(host, r)
		if targetIP == "" {
			ips, err := net.LookupHost(host)
			if err != nil || len(ips) == 0 {
				return nil, fmt.Errorf("DoT resolve %s: %w", host, err)
			}
			targetIP = ips[0]
		}
	}

	var conn net.Conn
	if portNum, err := strconv.Atoi(port); err == nil && portNum > 0 {
		if ip := net.ParseIP(targetIP); ip != nil {
			if proxyConn, used, dialErr := r.dialProxy(ip, portNum); used {
				if dialErr != nil {
					return nil, fmt.Errorf("DoT proxy dial: %w", dialErr)
				}
				conn = proxyConn
			}
		}
	}
	if conn == nil {
		var err error
		conn, err = dialer.Dial("tcp", net.JoinHostPort(targetIP, port))
		if err != nil {
			return nil, fmt.Errorf("DoT dial: %w", err)
		}
	}

	tlsConn := tls.Client(conn, &tls.Config{
		ServerName: host,
		MinVersion: tls.VersionTLS12,
	})
	defer tlsConn.Close()

	tlsConn.SetDeadline(time.Now().Add(queryTimeoutDoT))

	if err := tlsConn.Handshake(); err != nil {
		return nil, fmt.Errorf("DoT TLS handshake: %w", err)
	}

	lenBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBuf, uint16(len(rawQuery)))
	if _, err := tlsConn.Write(append(lenBuf, rawQuery...)); err != nil {
		return nil, fmt.Errorf("DoT write: %w", err)
	}

	if _, err := io.ReadFull(tlsConn, lenBuf); err != nil {
		return nil, fmt.Errorf("DoT read length: %w", err)
	}
	respLen := binary.BigEndian.Uint16(lenBuf)
	if respLen == 0 || respLen > 4096 {
		return nil, fmt.Errorf("DoT invalid response length: %d", respLen)
	}

	respBuf := make([]byte, respLen)
	if _, err := io.ReadFull(tlsConn, respBuf); err != nil {
		return nil, fmt.Errorf("DoT read response: %w", err)
	}

	return respBuf, nil
}

// queryDoQ sends a DNS query via DNS-over-QUIC (RFC 9250).
func (r *Resolver) queryDoQ(rawQuery []byte, doqURL string) ([]byte, error) {
	host, port := parseDoQURL(doqURL)
	if host == "" {
		return nil, fmt.Errorf("invalid DoQ URL: %s", doqURL)
	}

	conn, err := r.getOrCreateQUICConn(host, port)
	if err != nil {
		return nil, fmt.Errorf("DoQ connection: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), queryTimeoutDoQ)
	defer cancel()

	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		r.resetQUICConn()
		conn, err = r.getOrCreateQUICConn(host, port)
		if err != nil {
			return nil, fmt.Errorf("DoQ reconnect: %w", err)
		}
		stream, err = conn.OpenStreamSync(ctx)
		if err != nil {
			return nil, fmt.Errorf("DoQ stream: %w", err)
		}
	}

	lenBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBuf, uint16(len(rawQuery)))
	if _, err := stream.Write(append(lenBuf, rawQuery...)); err != nil {
		return nil, fmt.Errorf("DoQ write: %w", err)
	}
	stream.Close()

	respData, err := io.ReadAll(io.LimitReader(stream, 65535))
	if err != nil {
		return nil, fmt.Errorf("DoQ read: %w", err)
	}

	if len(respData) >= 2 {
		respLen := binary.BigEndian.Uint16(respData[:2])
		if int(respLen) == len(respData)-2 {
			return respData[2:], nil
		}
	}

	return respData, nil
}

func (r *Resolver) getOrCreateQUICConn(host, port string) (*quic.Conn, error) {
	r.quicMu.Lock()
	defer r.quicMu.Unlock()

	addr := net.JoinHostPort(host, port)
	if r.quicConn != nil && r.quicServer == addr {
		return r.quicConn, nil
	}

	targetIP := host
	if net.ParseIP(host) == nil {
		targetIP = resolveBootstrapIP(host, r)
		if targetIP == "" {
			ips, err := net.LookupHost(host)
			if err != nil || len(ips) == 0 {
				return nil, fmt.Errorf("DoQ resolve %s: %w", host, err)
			}
			targetIP = ips[0]
		}
	}

	udpAddr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(targetIP, port))
	if err != nil {
		return nil, fmt.Errorf("DoQ resolve UDP: %w", err)
	}

	udpConn, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, fmt.Errorf("DoQ listen: %w", err)
	}

	if r.protectSocketFn != nil {
		rawConn, err := udpConn.SyscallConn()
		if err == nil {
			rawConn.Control(func(fd uintptr) {
				r.protectSocketFn(int(fd))
			})
		}
	}

	tlsConf := &tls.Config{
		ServerName: host,
		NextProtos: []string{"doq"},
		MinVersion: tls.VersionTLS13,
	}

	ctx, cancel := context.WithTimeout(context.Background(), connectTimeout)
	defer cancel()

	transport := &quic.Transport{Conn: udpConn}
	conn, err := transport.Dial(ctx, udpAddr, tlsConf, &quic.Config{
		MaxIdleTimeout: 30 * time.Second,
	})
	if err != nil {
		udpConn.Close()
		return nil, fmt.Errorf("DoQ dial: %w", err)
	}

	r.quicConn = conn
	r.quicServer = addr
	return conn, nil
}

func (r *Resolver) resetQUICConn() {
	r.quicMu.Lock()
	defer r.quicMu.Unlock()

	if r.quicConn != nil {
		r.quicConn.CloseWithError(quic.ApplicationErrorCode(0), "reset")
		r.quicConn = nil
	}
}

func parseDoQURL(url string) (host, port string) {
	s := url
	for _, prefix := range []string{"quic://", "https://", "doq://"} {
		s = strings.TrimPrefix(s, prefix)
	}

	if idx := strings.IndexByte(s, '/'); idx >= 0 {
		s = s[:idx]
	}

	host, port, err := net.SplitHostPort(s)
	if err != nil {
		return s, "853"
	}
	return host, port
}

type protectedDialer struct {
	protectFn func(fd int) bool
	resolver  *Resolver
}

func (d *protectedDialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	dialer := &net.Dialer{
		Timeout: connectTimeout,
		Control: func(network, address string, c syscall.RawConn) error {
			if d.protectFn != nil {
				return c.Control(func(fd uintptr) {
					d.protectFn(int(fd))
				})
			}
			return nil
		},
	}

	targetAddr := addr
	host, port, err := net.SplitHostPort(addr)
	if err == nil {
		if net.ParseIP(host) == nil {
			targetIP := resolveBootstrapIP(host, d.resolver)
			if targetIP != "" {
				targetAddr = net.JoinHostPort(targetIP, port)
			}
		}

		if strings.HasPrefix(network, "tcp") && d.resolver != nil {
			targetHost, targetPort, splitErr := net.SplitHostPort(targetAddr)
			if splitErr == nil {
				if portNum, convErr := strconv.Atoi(targetPort); convErr == nil && portNum > 0 {
					ip := net.ParseIP(targetHost)
					if ip == nil {
						if ips, lookupErr := net.DefaultResolver.LookupIP(ctx, "ip4", targetHost); lookupErr == nil && len(ips) > 0 {
							ip = ips[0]
						}
					}
					if ip != nil {
						if conn, used, dialErr := d.resolver.dialProxy(ip, portNum); used {
							return conn, dialErr
						}
					}
				}
			}
		}
	}

	return dialer.DialContext(ctx, network, targetAddr)
}

func resolveBootstrapIP(host string, r *Resolver) string {
	if r != nil {
		r.mu.RLock()
		primary := r.primaryServer
		dohURL := r.dohURL
		r.mu.RUnlock()
		if net.ParseIP(primary) != nil && dohURL != "" {
			if u, err := url.Parse(dohURL); err == nil && strings.EqualFold(u.Hostname(), host) {
				return primary
			}
		}
	}
	switch strings.ToLower(host) {
	case "cloudflare-dns.com", "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com", "family.cloudflare-dns.com", "odoh.cloudflare-dns.com":
		return "1.1.1.1"
	case "dns.google":
		return "8.8.8.8"
	case "dns.quad9.net":
		return "9.9.9.9"
	case "dns.adguard-dns.com", "dns.adguard.com":
		return "94.140.14.14"
	case "doh.opendns.com", "dns.opendns.com":
		return "208.67.222.222"
	case "doh.familyshield.opendns.com":
		return "208.67.222.123"
	}
	return ""
}

// queryDoH3 sends a DNS query via DNS-over-HTTP/3 (RFC 9114 / RFC 8484 over HTTP/3).
func (r *Resolver) queryDoH3(rawQuery []byte, dohURL string) ([]byte, error) {
	if dohURL == "" {
		return nil, fmt.Errorf("DoH3 URL not configured")
	}

	targetURL := dohURL
	if strings.HasPrefix(strings.ToLower(targetURL), "h3://") {
		targetURL = "https://" + targetURL[5:]
	}

	var resp *http.Response
	var err error

	for attempt := 1; attempt <= 2; attempt++ {
		req, reqErr := http.NewRequest("POST", targetURL, bytes.NewReader(rawQuery))
		if reqErr != nil {
			return nil, fmt.Errorf("DoH3 request: %w", reqErr)
		}
		req.Header.Set("Content-Type", "application/dns-message")
		req.Header.Set("Accept", "application/dns-message")

		ctx, cancel := context.WithTimeout(context.Background(), queryTimeoutDoH)
		req = req.WithContext(ctx)

		resp, err = r.h3Client.Do(req)
		if err == nil {
			defer cancel()
			break
		}

		cancel()
		if attempt == 1 {
			time.Sleep(10 * time.Millisecond)
			continue
		}
		// If DoH3 fails (e.g. server doesn't support H3 or UDP blocked), fallback to HTTP/2 DoH
		return r.queryDoH(rawQuery, targetURL)
	}
	defer resp.Body.Close()

	if resp.StatusCode == 403 {
		return nil, fmt.Errorf("DoH3 rate limited (403)")
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("DoH3 status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return nil, fmt.Errorf("DoH3 read: %w", err)
	}

	return body, nil
}

