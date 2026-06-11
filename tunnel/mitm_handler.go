package tunnel

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
)

// ─────────────────────────────────────────────────────────────────────────────
// mitm_handler.go — Phase D flow-mode MITM handler.
//
// Replaces the Phase C direct-dial default for port 443/80 TCP flows
// when the stack is configured with a CertManager + MitmFilter via
// Engine.StartStackMitm. Decision flow mirrors the legacy MitmProxy
// (mitm_proxy.go handleConnect) but operates on terminated TCP flows
// from the userspace stack rather than HTTP CONNECT requests:
//
//   Gate 0: loopback / private IP → passthrough
//   Gate 1: port != 443/80        → passthrough
//   Gate 2: UID not in allowlist  → passthrough
//   Gate 3: peek first bytes      → classify TLS / HTTP, extract SNI / Host
//   Gate 4: ad-block blocker      → close
//   Gate 5: interception filter   → passthrough (sensitive / pinned domain)
//   Gate 6: local asset server    → serve from memory
//   Gate 7: MITM                  → TLS handshake with our cert, relay HTTP + inject
//
// Every non-MITM path uses the Phase C protected dialer so sockets
// bypass the VPN loop.
// ─────────────────────────────────────────────────────────────────────────────

const (
	// peekSize must be large enough to contain a full TLS ClientHello
	// including any SNI extension. 4 KB is the modern ceiling.
	peekSize = 4 * 1024

	// peekTimeout bounds how long we wait for the client's first bytes
	// before giving up and closing the flow. Browsers typically send
	// data within a few ms of the TCP SYN-ACK; a stuck flow here would
	// otherwise wedge a handler goroutine.
	peekTimeout = 10 * time.Second
)

// newMitmTcpHandler returns a TCP flow handler that MITMs HTTPS/HTTP
// flows for eligible apps and passes every other flow through directly
// with socket protection. The handler owns the flow for its lifetime.
func newMitmTcpHandler(
	certMgr *CertManager,
	filter *MitmFilter,
	blocker adBlockChecker,
	uidr UIDResolver,
	protectFn func(fd int) bool,
) TcpFlowHandler {
	return func(conn adapter.TCPConn) {
		defer conn.Close()

		flow := tcpFlowID(conn)
		uid := resolveFlowUID(uidr, ProtocolTCP, flow)

		// Gate 0 — never MITM private / loopback destinations. These
		// are local services (LAN printers, router admin pages) that
		// often have self-signed certs or none at all.
		if isLoopbackOrInternal(flow.serverIP.String()) {
			relayDirectFromFlow(conn, flow, blocker, protectFn)
			return
		}

		// Gate 1 — only attempt MITM on HTTP/HTTPS well-known ports.
		if flow.serverPort != 443 && flow.serverPort != 80 {
			relayDirectFromFlow(conn, flow, blocker, protectFn)
			return
		}

		// Gate 2 — browser allowlist (UID). When Kotlin has configured
		// an allowlist, non-allowed UIDs get passthrough. If UID is
		// unknown (API < 29, resolver failure), err on the safe side:
		// passthrough rather than MITM an unknown app.
		if filter.HasAllowedUIDs() && (uid == UIDUnknown || !filter.IsUIDAllowed(uid)) {
			relayDirectFromFlow(conn, flow, blocker, protectFn)
			return
		}

		// Gate 3 — peek first bytes to classify and extract SNI / Host.
		peeked, peekedReader, err := peekFlow(conn, peekSize, peekTimeout)
		if err != nil || len(peeked) == 0 {
			return // client closed before sending / timeout
		}

		sni := ""
		var classification flowClass
		if len(peeked) >= 3 && peeked[0] == 0x16 && peeked[1] == 0x03 {
			classification = classTLS
			sni = parseClientHelloSNI(peeked)
		} else if looksLikeHTTPRequest(peeked) {
			classification = classHTTP
			sni = parseHTTPHost(peeked)
		} else {
			// Unknown protocol on 443/80 — probably something proxied
			// through these ports that isn't TLS or HTTP. Passthrough.
			relayDirectPeeked(conn, peekedReader, flow, "", blocker, protectFn)
			return
		}

		hostname := sni
		if hostname == "" {
			// Fall back to destination IP when we couldn't parse a host.
			hostname = flow.serverIP.String()
		}
		hostname = strings.ToLower(strings.TrimSpace(hostname))

		// Gate 4 — ad-block blocker. Close the flow; the app's error
		// surface is equivalent to a network failure (browser shows
		// ERR_CONNECTION_REFUSED). No fabricated TLS error needed.
		if blocker != nil && blocker.IsDomainBlocked(hostname) {
			return
		}

		// Gate 5 — sensitive / cert-pinned domain → passthrough so the
		// client's own TLS validation succeeds.
		if !filter.IsInterceptionAllowed(hostname) {
			relayDirectPeeked(conn, peekedReader, flow, hostname, blocker, protectFn)
			return
		}

		// Gate 6 — local asset server (served entirely from memory).
		if IsLocalAssetHost(hostname) {
			if classification == classTLS {
				serveLocalAssetTLS(conn, peekedReader, certMgr, hostname)
			} else {
				serveLocalAssetPlaintext(conn, peekedReader)
			}
			return
		}

		// Gate 7 — MITM.
		if classification == classTLS {
			mitmTLSFlow(conn, peekedReader, certMgr, filter, blocker, hostname, flow, protectFn)
		} else {
			mitmHTTPFlow(conn, peekedReader, blocker, hostname, flow, protectFn)
		}
	}
}

type flowClass int

const (
	classUnknown flowClass = iota
	classTLS
	classHTTP
)

// ── Peek ─────────────────────────────────────────────────────────────────────

// peekFlow reads the first batch of client data (up to maxBytes) and
// returns both those bytes for classification and a Reader that
// replays them followed by anything the client sends next. A single
// conn.Read is issued; because TCP delivers the full TLS ClientHello
// or HTTP request line in its first segment in the common case, one
// Read normally suffices. Using bufio.Reader.Peek(maxBytes) would
// instead block until maxBytes arrive OR the deadline fires — adding
// seconds of latency to every flow when the client sends a typical
// 200-600B ClientHello.
//
// The caller continues to use conn for writes; only reads should come
// from the returned Reader.
func peekFlow(conn net.Conn, maxBytes int, timeout time.Duration) ([]byte, io.Reader, error) {
	conn.SetReadDeadline(time.Now().Add(timeout))
	defer conn.SetReadDeadline(time.Time{})

	buf := make([]byte, maxBytes)
	n, err := conn.Read(buf)
	if n == 0 {
		return nil, nil, err
	}
	peeked := buf[:n]
	// Replay: peeked bytes first, then whatever arrives on conn next.
	return peeked, io.MultiReader(bytes.NewReader(peeked), conn), nil
}

// ── TLS ClientHello SNI parsing ──────────────────────────────────────────────

// parseClientHelloSNI extracts the server_name extension from a TLS
// ClientHello record. Returns "" if the bytes aren't a ClientHello or
// the SNI extension is absent. No allocations on the unhappy path —
// this runs on every HTTPS connection.
func parseClientHelloSNI(record []byte) string {
	// TLS record layer: ContentType(1) Version(2) Length(2) Payload
	if len(record) < 5 || record[0] != 0x16 { // handshake
		return ""
	}
	recLen := int(binary.BigEndian.Uint16(record[3:5]))
	if recLen > len(record)-5 {
		recLen = len(record) - 5 // truncated but might still contain SNI
	}
	body := record[5 : 5+recLen]

	// Handshake header: Type(1) Length(3)
	if len(body) < 4 || body[0] != 0x01 { // 0x01 = ClientHello
		return ""
	}
	// We ignore the handshake length check — use body slice directly.
	ch := body[4:]

	// ClientHello:
	//   legacy_version(2) random(32) session_id(<=32 prefixed)
	//   cipher_suites cm extensions
	if len(ch) < 2+32+1 {
		return ""
	}
	p := 34
	sidLen := int(ch[p])
	p += 1 + sidLen
	if p+2 > len(ch) {
		return ""
	}
	csLen := int(binary.BigEndian.Uint16(ch[p : p+2]))
	p += 2 + csLen
	if p+1 > len(ch) {
		return ""
	}
	cmLen := int(ch[p])
	p += 1 + cmLen
	if p+2 > len(ch) {
		return ""
	}
	extLen := int(binary.BigEndian.Uint16(ch[p : p+2]))
	p += 2
	if p+extLen > len(ch) {
		extLen = len(ch) - p
	}
	ext := ch[p : p+extLen]

	// Scan extensions for server_name (0x0000).
	for len(ext) >= 4 {
		extType := binary.BigEndian.Uint16(ext[0:2])
		extDataLen := int(binary.BigEndian.Uint16(ext[2:4]))
		if 4+extDataLen > len(ext) {
			return ""
		}
		extData := ext[4 : 4+extDataLen]

		if extType == 0x0000 {
			// server_name extension body:
			//   list_len(2) [ name_type(1) name_len(2) name ]*
			if len(extData) < 5 {
				return ""
			}
			listLen := int(binary.BigEndian.Uint16(extData[0:2]))
			if 2+listLen > len(extData) {
				return ""
			}
			list := extData[2 : 2+listLen]
			if len(list) < 3 || list[0] != 0x00 {
				return "" // not host_name
			}
			nameLen := int(binary.BigEndian.Uint16(list[1:3]))
			if 3+nameLen > len(list) {
				return ""
			}
			return string(list[3 : 3+nameLen])
		}
		ext = ext[4+extDataLen:]
	}
	return ""
}

// ── HTTP Host extraction ─────────────────────────────────────────────────────

// looksLikeHTTPRequest returns true when the first bytes look like an
// HTTP/1.x request line.
func looksLikeHTTPRequest(b []byte) bool {
	if len(b) < 7 {
		return false
	}
	// Cheapest pattern: method token followed by a space, then /, and
	// later " HTTP/" somewhere in the peeked window. Catches every
	// standard verb without enumerating.
	for i := 0; i < len(b) && i < 16; i++ {
		if b[i] == ' ' {
			if i+2 < len(b) && b[i+1] == '/' {
				return true
			}
			return false
		}
		c := b[i]
		if !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
			return false
		}
	}
	return false
}

// parseHTTPHost extracts the Host header value from a raw HTTP request
// peek. Case-insensitive header-name match, trims whitespace. Returns
// "" if not found.
func parseHTTPHost(b []byte) string {
	idx := 0
	for idx < len(b) {
		// Find end of line.
		nl := -1
		for j := idx; j < len(b)-1; j++ {
			if b[j] == '\r' && b[j+1] == '\n' {
				nl = j
				break
			}
		}
		if nl < 0 {
			return ""
		}
		line := b[idx:nl]
		idx = nl + 2

		// Empty line → end of headers.
		if len(line) == 0 {
			return ""
		}

		// Skip the request line (first line has no colon before SP).
		// Header lines contain ':'.
		colon := -1
		for j := 0; j < len(line); j++ {
			if line[j] == ':' {
				colon = j
				break
			}
		}
		if colon <= 0 {
			continue
		}
		name := line[:colon]
		if strings.EqualFold(string(name), "Host") {
			value := strings.TrimSpace(string(line[colon+1:]))
			// Strip port, if any.
			if i := strings.IndexByte(value, ':'); i >= 0 {
				value = value[:i]
			}
			return value
		}
	}
	return ""
}

// ── Direct passthrough helpers ───────────────────────────────────────────────

// dialUpstream dials the flow's destination with socket protection,
// falling back to an IPv4 resolution of the hostname when the
// direct-IP dial fails. The common failure mode this rescues: Chrome
// resolves a dual-stack host (YouTube, Google, etc.) to IPv6, Android
// delivers the v6 packet to our TUN, the stack terminates with a v6
// serverIP, and the Go process's underlying network has no v6 route
// so dial returns "no route to host". Without the fallback the stack
// would RST the client, surfacing as ERR_CONNECTION_REFUSED.
//
// hostname may be "" (e.g., gates that trigger before SNI is known);
// in that case only the direct-IP dial is attempted.
func dialUpstream(flow flowID, hostname string, blocker adBlockChecker, protectFn func(fd int) bool) (net.Conn, error) {
	dialer := &net.Dialer{
		Timeout: flowDialTimeout,
		Control: protectedControl(protectFn),
	}
	dst := net.JoinHostPort(flow.serverIP.String(), intToStr(flow.serverPort))
	conn, err := dialer.Dial("tcp", dst)
	if err == nil {
		return conn, nil
	}

	// Direct-IP dial failed. Try the hostname-resolved-to-IPv4 path.
	if hostname != "" && blocker != nil && flow.serverIP.To4() == nil {
		if ip, lerr := blocker.lookupIP(hostname); lerr == nil && ip != nil {
			alt := net.JoinHostPort(ip.String(), intToStr(flow.serverPort))
			if altConn, aerr := dialer.Dial("tcp", alt); aerr == nil {
				logf("[TcpStack] v6 dial to %s failed (%v); fell back to v4 %s", dst, err, alt)
				return altConn, nil
			}
		}
	}
	logf("[TcpStack] upstream dial %s failed: %v", dst, err)
	return nil, err
}

// relayDirectFromFlow dials the flow's real destination and pipes
// bytes bidirectionally. No peek replay — used for gates that trigger
// before any read.
//
// No absolute SetDeadline is applied. tun2socks enables TCP keepalive
// on the gVisor side (60s idle, 30s interval, 9 probes) which kills
// genuinely stuck connections, but a healthy long-lived flow (video
// streaming, large download) is allowed to live as long as the apps
// need it. The previous 3-minute hard deadline was killing YouTube
// playback mid-stream and surfacing as ERR_CONNECTION_ABORTED.
func relayDirectFromFlow(clientConn net.Conn, flow flowID, blocker adBlockChecker, protectFn func(fd int) bool) {
	remote, err := dialUpstream(flow, "", blocker, protectFn)
	if err != nil {
		return
	}
	defer remote.Close()

	bidiCopyFlow(clientConn, remote)
}

// relayDirectPeeked dials the destination and writes the peeked bytes
// to it first, then pipes bidirectionally. Used after peek+classify
// when the classifier decides not to MITM. hostname is the SNI / Host
// (may be "") and enables IPv6→IPv4 fallback when the direct-IP dial
// fails.
func relayDirectPeeked(clientConn net.Conn, clientReader io.Reader, flow flowID, hostname string, blocker adBlockChecker, protectFn func(fd int) bool) {
	remote, err := dialUpstream(flow, hostname, blocker, protectFn)
	if err != nil {
		return
	}
	defer remote.Close()

	// client → remote: peeked bytes then stream
	done := make(chan struct{}, 2)
	go func() {
		io.Copy(remote, clientReader)
		if cw, ok := remote.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		io.Copy(clientConn, remote)
		if cw, ok := clientConn.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
	<-done
}

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
	// Dial the real server first so cert pinning checks catch obvious
	// problems before we commit to MITM. Uses the v6→v4 fallback so
	// dual-stack hosts still connect when the Go process has no v6
	// route on the underlying network.
	rawServer, err := dialUpstream(flow, hostname, blocker, protectFn)
	if err != nil {
		return
	}
	serverConn := tls.Client(rawServer, &tls.Config{
		ServerName:         hostname,
		InsecureSkipVerify: false,
	})
	if err := serverConn.Handshake(); err != nil {
		rawServer.Close()
		// Upstream cert failure → fall back to raw passthrough with
		// replay so the client's own TLS validation can surface a
		// meaningful error (or succeed).
		relayDirectPeeked(clientConn, clientReader, flow, hostname, blocker, protectFn)
		return
	}
	defer serverConn.Close()

	// Handshake with the client using our CA-signed cert. If the
	// client is pinning the real cert it will reject ours; auto-
	// blacklist so future flows to this host go direct.
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

// relayHTTPFlow is the flow-mode equivalent of MitmProxy.relayHTTP —
// reads HTTP requests from the client connection, forwards to the
// server, decompresses and injects into HTML responses, and supports
// local.pwhs.app sub-requests inside the same session. Same logic as
// the legacy path, duplicated here so Phase E can remove the legacy
// version without breaking this one.
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

		// Sub-request local asset inline.
		if IsLocalAssetHost(reqHost) {
			resp := ServeLocalAsset(req)
			resp.Write(clientConn)
			continue
		}

		// Sub-request ad block.
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
// reader first, then falls through to the connection. Write, Close,
// and deadlines pass through to the underlying conn unchanged.
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
