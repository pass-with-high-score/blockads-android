package mitm

import (
	"bufio"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"strings"
)

// ── Local asset server over flow ─────────────────────────────────────────────

// serveLocalAssetTLS answers a TLS connection whose SNI matches the
// local asset host. A dynamic cert is minted and the handshake is
// completed; the served body is produced entirely from in-memory
// cosmetic CSS without any upstream dial.
func serveLocalAssetTLS(conn net.Conn, clientReader io.Reader, certMgr *CertManager, hostname string) {
	tlsCfg := certMgr.GetDynamicTLSConfigForHost(hostname)
	clientTLS := tls.Server(&peekReplayConn{Conn: conn, r: clientReader}, tlsCfg)
	if err := clientTLS.Handshake(); err != nil {
		return
	}
	defer clientTLS.Close()

	rb := bufio.NewReader(clientTLS)
	for {
		req, err := http.ReadRequest(rb)
		if err != nil {
			return
		}
		resp := ServeLocalAsset(req)
		if err := resp.Write(clientTLS); err != nil {
			return
		}
		if req.Close {
			return
		}
	}
}

// serveLocalAssetPlaintext answers an HTTP (no TLS) request targeting
// the local asset host. Kept for symmetry; normally the local asset
// host is only accessed via HTTPS links.
func serveLocalAssetPlaintext(conn net.Conn, clientReader io.Reader) {
	rb := bufio.NewReader(clientReader)
	for {
		req, err := http.ReadRequest(rb)
		if err != nil {
			return
		}
		resp := ServeLocalAsset(req)
		if err := resp.Write(conn); err != nil {
			return
		}
		if req.Close {
			return
		}
	}
}

// ── MITM TLS flow ────────────────────────────────────────────────────────────

// mitmTLSFlow performs the TLS handshake with the client using our
// dynamic cert, dials the real server with TLS validation, and relays
// HTTP request/response pairs — injecting cosmetic CSS into HTML bodies
// via the shared helpers from mitm_proxy.go.
func mitmTLSFlow(
	clientConn net.Conn,
	clientReader io.Reader,
	certMgr *CertManager,
	filter *MitmFilter,
	blocker adBlockChecker,
	hostname string,
	flow flowID,
	protectFn func(fd int) bool,
) {
	rawServer, err := dialUpstream(flow, hostname, blocker, protectFn)
	if err != nil {
		return
	}

	clientCertRequested := false
	serverConn := tls.Client(rawServer, upstreamTLSConfig(hostname, &clientCertRequested))
	if err := serverConn.Handshake(); err != nil {
		rawServer.Close()
		relayDirectPeeked(clientConn, clientReader, flow, hostname, blocker, protectFn)
		return
	}
	defer serverConn.Close()

	if state := serverConn.ConnectionState(); len(state.PeerCertificates) > 0 {
		leaf := state.PeerCertificates[0]
		if clientCertRequested || isExtendedValidation(leaf) {
			reason := "EV certificate"
			if clientCertRequested {
				reason = "client-certificate (mTLS) request"
			}
			logf("MITM: not filtering '%s' — %s; passthrough", hostname, reason)
			filter.BlacklistDomain(hostname)
			serverConn.Close()
			relayDirectPeeked(clientConn, clientReader, flow, hostname, blocker, protectFn)
			return
		}
	}

	tlsCfg := certMgr.GetDynamicTLSConfigForHost(hostname)
	clientTLS := tls.Server(&peekReplayConn{Conn: clientConn, r: clientReader}, tlsCfg)
	if err := clientTLS.Handshake(); err != nil {
		errStr := err.Error()
		if strings.Contains(errStr, "unknown certificate") ||
			strings.Contains(errStr, "handshake failure") ||
			strings.Contains(errStr, "certificate unknown") ||
			strings.Contains(errStr, "bad certificate") ||
			strings.Contains(errStr, "tls:") {
			filter.BlacklistDomain(hostname)
		}
		return
	}
	defer clientTLS.Close()

	relayHTTPFlow(clientTLS, serverConn, hostname, blocker)
}

// mitmHTTPFlow handles plaintext HTTP (port 80) flows. Same gates
// and injection as mitmTLSFlow but no TLS.
func mitmHTTPFlow(
	clientConn net.Conn,
	clientReader io.Reader,
	blocker adBlockChecker,
	hostname string,
	flow flowID,
	protectFn func(fd int) bool,
) {
	serverConn, err := dialUpstream(flow, hostname, blocker, protectFn)
	if err != nil {
		return
	}
	defer serverConn.Close()

	relayHTTPFlow(&peekReplayConn{Conn: clientConn, r: clientReader}, serverConn, hostname, blocker)
}

// relayHTTPFlow reads HTTP requests from the client connection, forwards to
// the server, decompresses and injects into HTML responses, and supports
// local.pwhs.app sub-requests inside the same session.
func relayHTTPFlow(clientConn, serverConn net.Conn, hostname string, blocker adBlockChecker) {
	cr := bufio.NewReader(clientConn)
	sr := bufio.NewReader(serverConn)

	for {
		req, err := http.ReadRequest(cr)
		if err != nil {
			return
		}
		if req.Host == "" {
			req.Host = hostname
		}

		reqHost := req.Host
		if i := strings.IndexByte(reqHost, ':'); i >= 0 {
			reqHost = reqHost[:i]
		}

		if IsLocalAssetHost(reqHost) {
			resp := ServeLocalAsset(req)
			resp.Write(clientConn)
			continue
		}

		if blocker != nil && reqHost != hostname && blocker.IsDomainBlocked(reqHost) {
			blockedResp := &http.Response{
				StatusCode: 403,
				ProtoMajor: 1, ProtoMinor: 1,
				Header: make(http.Header),
				Body:   io.NopCloser(strings.NewReader("Blocked by BlockAds")),
			}
			blockedResp.Header.Set("Connection", "keep-alive")
			blockedResp.Header.Set("Content-Length", "19")
			blockedResp.Write(clientConn)
			continue
		}

		if requestAcceptsHTML(req) {
			req.Header.Del("Accept-Encoding")
		}

		SanitizeRequest(req, hostname)

		if err := req.Write(serverConn); err != nil {
			return
		}

		resp, err := http.ReadResponse(sr, req)
		if err != nil {
			return
		}
		if ShouldInjectHTML(resp.Header.Get("Content-Type")) {
			wrapResponseForInjection(resp)
		}
		if err := resp.Write(clientConn); err != nil {
			resp.Body.Close()
			return
		}
		resp.Body.Close()

		if resp.Close || req.Close {
			return
		}
	}
}

// ── Utilities ────────────────────────────────────────────────────────────────

// peekReplayConn wraps a net.Conn so Read yields bytes from the peeked
// reader first, then falls through to the connection.
type peekReplayConn struct {
	net.Conn
	r io.Reader
}

func (c *peekReplayConn) Read(b []byte) (int, error) {
	return c.r.Read(b)
}

// intToStr converts a positive int to decimal ASCII without strconv
// allocation overhead in the hot path.
func intToStr(i int) string {
	if i == 0 {
		return "0"
	}
	var buf [20]byte
	pos := len(buf)
	neg := i < 0
	if neg {
		i = -i
	}
	for i > 0 {
		pos--
		buf[pos] = byte('0' + i%10)
		i /= 10
	}
	if neg {
		pos--
		buf[pos] = '-'
	}
	return string(buf[pos:])
}
